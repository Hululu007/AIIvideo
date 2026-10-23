"""请求体 → 入库行。

这一层只做「合不合法」和「怎么拆列」，不碰数据库、不碰 HTTP。
输入的 JSON 是**外部数据**：每个字段都要自己判断类型，别信它长得像什么。
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any

from psycopg.types.json import Jsonb

from .config import MAX_EVENTS, MAX_EVENT_BYTES

# 2000-01-01 到 2100-01-01。超出这个范围的时间戳一定是客户端时钟坏了，
# 让它进库会把 events_daily 切出一堆 1970 年的行。
_MIN_AT = 946684800000
_MAX_AT = 4102444800000


class IngestError(Exception):
    """带 HTTP 状态码的校验失败。message 会原样回给客户端 —— 得是人话。"""

    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


def _clean(value: str) -> str:
    # Postgres 的 text 存不了 \u0000，客户端塞一个就让整批 500。
    return value.replace("\x00", "")


def _text(value: Any, field: str, limit: int, required: bool = True) -> str | None:
    if value is None:
        if required:
            raise IngestError(400, f"{field} 不能为空")
        return None
    if not isinstance(value, str):
        raise IngestError(400, f"{field} 必须是字符串")
    text = _clean(value).strip()
    if not text:
        if required:
            raise IngestError(400, f"{field} 不能是空串")
        return None
    if len(text) > limit:
        raise IngestError(400, f"{field} 超过 {limit} 个字符")
    return text


def _int(value: Any) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float) and value.is_integer():
        return int(value)
    return None


def _optional_app(payload: dict) -> dict:
    app = payload.get("app")
    return app if isinstance(app, dict) else {}


def parse_batch(payload: Any, device_id: str) -> list[dict]:
    """返回可以直接喂给 executemany 的字典列表。"""
    if not isinstance(payload, dict):
        raise IngestError(400, "请求体必须是一个 JSON 对象")

    events = payload.get("events")
    if not isinstance(events, list):
        raise IngestError(400, "events 必须是数组")
    if not events:
        raise IngestError(400, "events 是空的，没有东西可收")
    # 条数是「大小」那一条限制的另一半，所以和字节数一样是 413。
    if len(events) > MAX_EVENTS:
        raise IngestError(413, f"一次最多 {MAX_EVENTS} 条，这次 {len(events)} 条")

    app = _optional_app(payload)
    app_version = _text(app.get("v"), "app.v", 32, required=False)
    app_vc = _int(app.get("vc"))
    os_version = _int(app.get("os"))
    model = _text(app.get("model"), "app.model", 64, required=False)
    patch = _int(app.get("patch"))

    rows: list[dict] = []
    for index, event in enumerate(events, start=1):
        if not isinstance(event, dict):
            raise IngestError(400, f"第 {index} 条不是对象")

        serialized = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
        size = len(serialized.encode("utf-8"))
        if size > MAX_EVENT_BYTES:
            raise IngestError(
                413,
                f"第 {index} 条序列化后 {size} 字节，超过单条 {MAX_EVENT_BYTES} 字节的限制",
            )

        event_id = _text(event.get("id"), f"第 {index} 条的 id", 128)
        at = _int(event.get("at"))
        if at is None:
            raise IngestError(400, f"第 {index} 条的 at 必须是毫秒时间戳")
        if not (_MIN_AT <= at <= _MAX_AT):
            raise IngestError(400, f"第 {index} 条的 at 不在合理范围（2000~2100 年）")

        props = event.get("props")
        if props is None:
            props = {}
        if not isinstance(props, dict):
            raise IngestError(400, f"第 {index} 条的 props 必须是对象")

        rows.append({
            "event_id": event_id,
            "session_id": _text(event.get("session"), f"第 {index} 条的 session", 128, required=False),
            "at": datetime.fromtimestamp(at / 1000, tz=timezone.utc),
            # App 端（Report.java）把 device 放在**每条事件**里，不是请求顶层，
            # 也不发 X-Device 头。三处都认，逐条优先。
            "device_id": _text(event.get("device") or event.get("device_id"),
                               f"第 {index} 条的 device", 128, required=False) or device_id,
            "kind": _text(event.get("kind"), f"第 {index} 条的 kind", 64),
            "name": _text(event.get("name"), f"第 {index} 条的 name", 96),
            "source_id": _text(event.get("source"), f"第 {index} 条的 source", 128, required=False),
            # 常用字段抽成列：查「哪个源哪个阶段失败」不用每次开 jsonb。
            "stage": _text(event.get("stage") or props.get("stage"), f"第 {index} 条的 stage", 64, required=False),
            "code": _text(event.get("code") or props.get("code"), f"第 {index} 条的 code", 64, required=False),
            "ms": _int(event.get("ms") if event.get("ms") is not None else props.get("ms")),
            "props": Jsonb(props),  # 原始 props 整份留着，抽成列不代表丢掉其余的
            "app_version": app_version,
            "app_vc": app_vc,
            "os": os_version,
            "model": model,
            "patch": patch,
        })
    return rows


def device_of(headers_device: str | None, payload: Any) -> str:
    """兜底的 device_id：X-Device 头优先，其次请求顶层的 device/device_id。

    事件自己带了 device 的时候（App 就是这么发的）用事件里的，这个函数管不着。
    表里 device_id 是 not null，宁可留一个 'unknown' 也不要因为这行丢掉事件。
    """
    if headers_device:
        cleaned = _clean(headers_device).strip()
        if cleaned:
            return cleaned[:128]
    if isinstance(payload, dict):
        body_device = payload.get("device") or payload.get("device_id")
        if isinstance(body_device, str) and body_device.strip():
            return _clean(body_device).strip()[:128]
    return "unknown"

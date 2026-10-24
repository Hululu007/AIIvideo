"""埋点服务的只读接口（X-Stats-Token）。

后台是 network_mode: host，够不着 track-db（那个库一步都没出 compose 网络），
所以数据从 track 服务的 /stats/* 拿。这里只做请求和错误翻译，不猜口径 ——
「近 N 天按哪个时区切」「devices 是设备不是人」这些都写在 track 那边的 stats.py 里。

失败不抛异常：页面上要能同时显示「概览挂了但事件流还在」这种半死状态，
所以每个函数回 (数据, 错误说明)，由模板决定怎么展示。
"""
from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

from .config import TRACK_TIMEOUT, TRACK_TOKEN, TRACK_URL


def _get(path: str, params: dict[str, Any]) -> tuple[Any, str]:
    if not TRACK_URL:
        return None, "没配 TRACK_URL（后台读不到埋点服务）"
    if not TRACK_TOKEN:
        return None, "没配 STATS_TOKEN（埋点服务那边的 /stats/* 关着）"
    url = f"{TRACK_URL.rstrip('/')}{path}?{urllib.parse.urlencode(params)}"
    request = urllib.request.Request(url, headers={"X-Stats-Token": TRACK_TOKEN})
    try:
        with urllib.request.urlopen(request, timeout=TRACK_TIMEOUT) as response:
            return json.loads(response.read().decode("utf-8")), ""
    except urllib.error.HTTPError as exc:
        detail = ""
        try:
            detail = json.loads(exc.read().decode("utf-8")).get("error", "")
        except Exception:  # noqa: BLE001
            pass
        return None, f"埋点服务回 {exc.code}：{detail or exc.reason}"
    except Exception as exc:  # noqa: BLE001 —— 连不上/超时都归一类
        return None, f"连不上埋点服务：{exc}"


def overview(days: int = 7) -> tuple[Any, str]:
    return _get("/stats/overview", {"days": days})


def events(limit: int = 80, kind: str = "", clean: bool = False) -> tuple[Any, str]:
    data, error = _get("/stats/events", {"limit": limit, "kind": kind, "clean": 1 if clean else 0})
    return (data or {}).get("items", []), error

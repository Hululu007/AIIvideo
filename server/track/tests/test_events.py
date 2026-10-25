"""`ingest.parse_batch` 的契约：哪些问题拒整批，哪些只拒一条。

背景（2026-10-25）：一条 `props.code` 是数字的播放失败，会让同批 199 条好事件
一起 400；客户端对 4xx 的处理是「丢掉」。结果线上 `kind='play'` 一条都没有 ——
不是没触发，是每次触发都在炸自己的批次。所以这两类的分界是这份测试的重点。

跑法（不需要数据库、不需要网络）：
    cd server/track && .venv/bin/python -m unittest discover -s tests -v
"""
from __future__ import annotations

import json
import sys
import time
import unittest
from pathlib import Path

# 让 `from app.ingest import ...` 能找到 web/ 下的包。psycopg 只被 Jsonb 用到，
# 装了才能 import —— .venv 里有。
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "web"))

from app import ingest  # noqa: E402


def event(**over):
    base = {
        "id": "evt-" + str(time.time_ns()),
        "at": 1761278400000,
        "session": "sess-1",
        "kind": "bridge",
        "name": "command_error",
        "source": "51cg",
        "props": {"stage": "spider", "code": "IOException", "msg": "ok"},
        "device": "dev-1",
    }
    base.update(over)
    return base


def batch(events):
    return {"app": {"v": "2.0.22", "vc": 20022, "os": 36, "model": "23113RKC6C"}, "events": events}


class BatchLevelTest(unittest.TestCase):
    """这些是整个请求没救的情形，仍然抛 IngestError（400/413）。"""

    def test_请求体不是对象(self) -> None:
        with self.assertRaises(ingest.IngestError) as ctx:
            ingest.parse_batch([], "d")
        self.assertEqual(400, ctx.exception.status)

    def test_events_不是数组(self) -> None:
        with self.assertRaises(ingest.IngestError) as ctx:
            ingest.parse_batch({"events": "x"}, "d")
        self.assertEqual(400, ctx.exception.status)

    def test_events_是空的(self) -> None:
        with self.assertRaises(ingest.IngestError) as ctx:
            ingest.parse_batch({"events": []}, "d")
        self.assertIn("没有东西可收", ctx.exception.message)

    def test_条数超限是_413(self) -> None:
        with self.assertRaises(ingest.IngestError) as ctx:
            ingest.parse_batch(batch([event() for _ in range(ingest.MAX_EVENTS + 1)]), "d")
        self.assertEqual(413, ctx.exception.status)


class PerEventTest(unittest.TestCase):
    """单条问题只拒那一条，其余照收 —— 这是本轮修的核心。"""

    def test_数字错误码照收(self) -> None:
        # feed-player.js:517 trackPlayError('decode', null, {code: 3, ...})
        # video.error.code 是 MediaError 枚举，天生是数字。
        rows, rejected = ingest.parse_batch(batch([
            event(kind="play", name="play_error",
                  props={"stage": "decode", "code": 3, "why": "网络错误", "ep": 2}),
        ]), "d")
        self.assertEqual(1, len(rows))
        self.assertEqual([], rejected)
        self.assertEqual("3", rows[0]["code"])

    def test_浮点整数错误码也认(self) -> None:
        rows, _ = ingest.parse_batch(batch([event(props={"code": 3.0})]), "d")
        self.assertEqual("3", rows[0]["code"])

    def test_布尔错误码拒单条(self) -> None:
        rows, rejected = ingest.parse_batch(batch([event(props={"code": True})]), "d")
        self.assertEqual([], rows)
        self.assertEqual(1, len(rejected))
        self.assertIn("布尔", rejected[0]["reason"])

    def test_一条坏不该连坐整批(self) -> None:
        rows, rejected = ingest.parse_batch(batch([
            event(name="app_open", kind="app", props={}),
            event(at=1),                       # 时钟坏了
            event(name="command_cancelled", props={"stage": "spider"}),
        ]), "d")
        self.assertEqual(2, len(rows))
        self.assertEqual(1, len(rejected))
        self.assertEqual(2, rejected[0]["index"])
        self.assertIn("at", rejected[0]["reason"])

    def test_超预算的崩溃只拒它自己(self) -> None:
        # 真实的 Android 堆栈：41 帧 × 70 字符 + 中文异常消息，序列化后 4400+ 字节。
        # 线上只有手搓的十帧 diag 崩溃过得去（958 字节），真崩溃全被 413 掉。
        frame = "x" * 70 + "\n"
        trace = frame * 40 + "caused by: java.lang.NullPointerException\n" + "  at " + frame * 10
        rows, rejected = ingest.parse_batch(batch([
            event(kind="app", name="app_open", props={}),
            event(kind="crash", name="uncaught",
                  props={"stage": "uncaught", "code": "NullPointerException",
                         "msg": "中" * 200, "trace": trace, "thread": "main"}),
        ]), "d")
        self.assertEqual(1, len(rows))
        self.assertEqual("app_open", rows[0]["name"])
        self.assertEqual(1, len(rejected))
        self.assertEqual("crash", rejected[0]["kind"])
        self.assertIn("4096", rejected[0]["reason"])

    def test_坏事件带上_kind_name_方便归因(self) -> None:
        _, rejected = ingest.parse_batch(batch([event(kind="play", name="play_error", props="不是对象")]), "d")
        self.assertEqual("play", rejected[0]["kind"])
        self.assertEqual("play_error", rejected[0]["name"])

    def test_props_非对象只拒单条(self) -> None:
        rows, rejected = ingest.parse_batch(batch([event(props=[1, 2, 3])]), "d")
        self.assertEqual([], rows)
        self.assertIn("props 必须是对象", rejected[0]["reason"])

    def test_缺_id_只拒单条(self) -> None:
        bad = event()
        del bad["id"]
        rows, rejected = ingest.parse_batch(batch([bad, event()]), "d")
        self.assertEqual(1, len(rows))
        self.assertEqual(1, len(rejected))


class FieldTest(unittest.TestCase):
    def test_阶段和耗时从_props_抽成列(self) -> None:
        rows, _ = ingest.parse_batch(batch([
            event(props={"stage": "cover", "code": "SocketException", "ms": 1234}),
        ]), "d")
        row = rows[0]
        self.assertEqual("cover", row["stage"])
        self.assertEqual("SocketException", row["code"])
        self.assertEqual(1234, row["ms"])
        # 抽成列不等于丢掉其余：props 整份留着。
        self.assertIsNotNone(row["props"])

    def test_事件里的_device_优先于请求顶层(self) -> None:
        rows, _ = ingest.parse_batch(batch([event(device="每条自带的")]), "兜底的")
        self.assertEqual("每条自带的", rows[0]["device_id"])

    def test_没有_device_时回落到兜底值(self) -> None:
        one = event()
        del one["device"]
        rows, _ = ingest.parse_batch(batch([one]), "兜底的")
        self.assertEqual("兜底的", rows[0]["device_id"])

    def test_app_元数据坏了不该影响事件(self) -> None:
        # 老客户端可能把 model 写超长，那是装饰字段。
        payload = batch([event()])
        payload["app"]["model"] = "m" * 500
        rows, rejected = ingest.parse_batch(payload, "d")
        self.assertEqual(1, len(rows))
        self.assertEqual([], rejected)
        self.assertIsNone(rows[0]["model"])


class MachineReadableShapeTest(unittest.TestCase):
    """rejected 里每一项目前只有 index/reason/kind/name —— 别悄悄改形状。"""

    def test_形状(self) -> None:
        _, rejected = ingest.parse_batch(batch([event(at=None)]), "d")
        self.assertEqual({"index", "reason", "kind", "name"}, set(rejected[0]))

    def test_能直接_json_序列化(self) -> None:
        _, rejected = ingest.parse_batch(batch([event(at=None)]), "d")
        json.dumps(rejected, ensure_ascii=False)  # main.py 会把它塞进响应


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""验收脚本：按契约把每条边都打一遍。

    # 本机进程
    TRACK_SECRET=<server .env 里那串> python3 acceptance.py http://127.0.0.1:8092
    # 公网（签名/限流一样，只是多过一层 Caddy）
    TRACK_SECRET=... python3 acceptance.py https://149.104.21.177.sslip.io/t

只依赖标准库。数据库那几条断言打印成 psql 命令，自己粘进容器里跑 ——
脚本不去猜服务器上怎么连库。

限流那条会连打 70 次，跑完这个 IP 在接下来一分钟里是被限的，放在最后。
"""
from __future__ import annotations

import gzip
import hashlib
import hmac
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8092").rstrip("/")
SECRET = os.environ.get("TRACK_SECRET", "")
DEVICE = "acceptance-" + uuid.uuid4().hex[:8]
PASS, FAIL = [], []


def check(name: str, ok: bool, detail: str = "") -> None:
    (PASS if ok else FAIL).append(name)
    print(f"{'PASS' if ok else 'FAIL'}  {name}{'  ' + detail if detail else ''}")


def sign(body: bytes) -> str:
    return hmac.new(SECRET.encode(), body, hashlib.sha256).hexdigest()


def post(body: bytes, *, sig: str | None = None, gz: bool = False,
         device: str | None = DEVICE) -> tuple[int, dict]:
    payload = gzip.compress(body) if gz else body
    headers = {"Content-Type": "application/json", "X-Sig": sig if sig is not None else sign(payload)}
    if gz:
        headers["Content-Encoding"] = "gzip"
    if device:
        headers["X-Device"] = device
    request = urllib.request.Request(f"{BASE}/events", data=payload, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            raw = response.read().decode() or "{}"
            return response.status, json.loads(raw)
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode() or "{}"
        try:
            return exc.code, json.loads(raw)
        except json.JSONDecodeError:
            return exc.code, {"raw": raw}


def event(**over) -> dict:
    base = {
        "id": str(uuid.uuid4()),
        "at": int(time.time() * 1000),
        "session": str(uuid.uuid4()),
        "kind": "play",
        "name": "play_error",
        "source": "51cg",
        "props": {"stage": "resolve", "code": "timeout", "ms": 8000},
    }
    base.update(over)
    return base


def batch(events: list[dict]) -> bytes:
    return json.dumps({
        "app": {"v": "2.0.19", "vc": 20019, "os": 34, "model": "23113RKC6C", "patch": 0},
        "events": events,
    }, separators=(",", ":")).encode()


def main() -> int:
    if not SECRET:
        print("TRACK_SECRET 没设，先 export 再跑")
        return 2

    # healthz
    try:
        with urllib.request.urlopen(f"{BASE}/healthz", timeout=10) as response:
            body = json.loads(response.read())
        check("healthz 200", response.status == 200 and body.get("ok") is True, json.dumps(body))
    except Exception as exc:  # noqa: BLE001
        check("healthz 200", False, repr(exc))
        return 1

    # 正确签名 + 2 条
    two = [event(), event(name="play_start", props={"stage": "first_frame", "ms": 1200})]
    status, body = post(batch(two))
    check("2 条合法事件 → 200 accepted:2",
          status == 200 and body.get("ok") is True and body.get("accepted") == 2, json.dumps(body))

    # 错签名
    status, body = post(batch([event()]), sig="0" * 64)
    check("错签名 → 401", status == 401 and body.get("ok") is False, json.dumps(body))

    # gzip
    status, body = post(batch([event(name="app_open")]), gz=True)
    check("gzip 请求体 → 200", status == 200 and body.get("accepted") == 1, json.dumps(body))

    # 300 条
    big = [event() for _ in range(300)]
    status, body = post(batch(big))
    check("300 条 → 413 且说明条数限制", status == 413 and "200" in body.get("error", ""), json.dumps(body, ensure_ascii=False))

    # 单条超 4KB
    fat = event(props={"blob": "x" * 5000})
    status, body = post(batch([fat]))
    check("单条超 4KB → 413 且说明哪一条", status == 413 and "4096" in body.get("error", ""), json.dumps(body, ensure_ascii=False))

    # 重发同一个 event_id
    dup = event(name="dup_probe")
    status1, body1 = post(batch([dup]))
    status2, body2 = post(batch([dup]))
    check("同一 event_id 发两次：第一次 accepted:1，第二次 accepted:0",
          status1 == 200 and body1.get("accepted") == 1 and status2 == 200 and body2.get("accepted") == 0,
          f"{json.dumps(body1)} / {json.dumps(body2)}")

    # 坏 JSON
    status, body = post(b"{not json")
    check("坏 JSON → 400 人话", status == 400 and body.get("error"), json.dumps(body, ensure_ascii=False))

    # 限流：并发 70 次。串行打不出 429 —— 走公网时一次往返接近 1 秒，
    # 70 次能拖过 60 秒的窗口；那不是限流失效，是压力不够。
    codes: list[int] = []
    lock = threading.Lock()

    def blast() -> None:
        status, _body = post(batch([event()]))
        with lock:
            codes.append(status)

    threads = [threading.Thread(target=blast) for _ in range(70)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    check("并发 70 次出现 429", 429 in codes, f"200×{codes.count(200)} 429×{codes.count(429)}")
    status, body = post(batch([event()]))
    check("超限后仍是 429", status == 429, json.dumps(body, ensure_ascii=False))

    print()
    print("数据库侧自己跑：")
    print(f"  ssh relay \"docker exec track-db-1 psql -U track -d track -c \\\"select event_id, kind, name, source_id, stage, code, ms, props from events where device_id = '{DEVICE}' order by id desc limit 10\\\"\"")
    print(f"  ssh relay \"docker exec track-db-1 psql -U track -d track -c \\\"select count(*) from events where event_id = '{dup['id']}'\\\"\"")
    print()
    print(f"通过 {len(PASS)} 条，失败 {len(FAIL)} 条")
    for name in FAIL:
        print("  失败：", name)
    return 0 if not FAIL else 1


if __name__ == "__main__":
    raise SystemExit(main())

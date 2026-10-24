"""埋点上报服务。

对外只有两个路由：

    POST /events    批量上报（Caddy 的 handle_path /t/* 会把 /t 剥掉）
    GET  /healthz   存活

── 校验顺序是契约的一部分 ──────────────────────────────────────────────
方法 → 大小 → 签名 → 限流 → 解析 → 入库。

这个次序不是为了好看：签名排在小请求体之后、限流之前，意味着
  · 坏签名的请求不消耗配额（被扫描器刷不会把真用户挤掉）；
  · 超大请求不花 CPU 去算 HMAC 和解析 JSON。
改顺序等于改攻击面，别动。

── 失败只回人话 ──────────────────────────────────────────────────────
所有错误都是 {"ok": false, "error": "..."}，堆栈只进容器日志。
"""
from __future__ import annotations

import asyncio
import gzip
import hashlib
import hmac
import json
import logging
import zlib
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.concurrency import run_in_threadpool

from . import db, ingest, jobs, ratelimit, stats
from .config import MAX_BODY_BYTES, MAX_INFLATED_BYTES, STATS_TOKEN, TRACK_SECRET

log = logging.getLogger("track")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


@asynccontextmanager
async def lifespan(_app: FastAPI):
    db.init()
    task = asyncio.create_task(jobs.scheduler())
    log.info("埋点服务就绪")
    try:
        yield
    finally:
        task.cancel()


app = FastAPI(title="埋点上报", docs_url=None, redoc_url=None, openapi_url=None,
              lifespan=lifespan)


def fail(status: int, message: str, headers: dict | None = None) -> JSONResponse:
    return JSONResponse({"ok": False, "error": message}, status_code=status, headers=headers)


# 框架自己的 404/405 默认回 {"detail": "Not Found"}，形状和契约里的不一样。
# 客户端只认 {"ok": false, "error": "..."}，所以在这里统一。
_HTTP_HINTS = {404: "没有这个接口", 405: "只接受 POST"}


@app.exception_handler(StarletteHTTPException)
async def http_error(_request: Request, exc: StarletteHTTPException) -> JSONResponse:
    return fail(exc.status_code, _HTTP_HINTS.get(exc.status_code, str(exc.detail)))


@app.exception_handler(Exception)
async def unhandled_error(_request: Request, exc: Exception) -> JSONResponse:
    log.exception("未处理异常：%s", exc)
    return fail(500, "服务内部出错，已经记进日志")


def client_ip(request: Request) -> str:
    """Caddy 会带 X-Forwarded-For，取第一段（最左边的原始客户端）。"""
    forwarded = request.headers.get("x-forwarded-for", "")
    if forwarded:
        first = forwarded.split(",")[0].strip()
        if first:
            return first
    return request.client.host if request.client else "unknown"


def signature_ok(body: bytes, inflated: bytes | None, provided: str) -> bool:
    """比对 X-Sig。

    签的是**线上那串字节**。gzip 请求体有两种算法都说得通（对压缩后的算、对
    解压后的算），客户端到底怎么算的没法在这一侧判定，所以两个都认 —— 只多花
    一次 HMAC，不放松任何东西：两种都算不出来照样 401。
    """
    if not provided:
        return False
    candidate = provided.strip().lower()
    if candidate.startswith("sha256="):
        candidate = candidate[len("sha256="):]
    for raw in (body, inflated):
        if raw is None:
            continue
        expected = hmac.new(TRACK_SECRET.encode(), raw, hashlib.sha256).hexdigest()
        if hmac.compare_digest(expected, candidate):
            return True
    return False


def inflate(raw: bytes) -> tuple[bytes | None, JSONResponse | None]:
    """按 Content-Encoding 解压。解压后大小也要有上限，否则是压缩炸弹。"""
    if len(raw) > MAX_BODY_BYTES:
        return None, fail(413, f"请求体 {len(raw)} 字节，超过 {MAX_BODY_BYTES} 字节的上限")
    if not raw:
        return None, fail(400, "请求体是空的")
    try:
        data = gzip.decompress(raw)
    except (OSError, EOFError, zlib.error):
        return None, fail(400, "Content-Encoding: gzip 但请求体不是合法的 gzip")
    if len(data) > MAX_INFLATED_BYTES:
        return None, fail(413, f"解压后 {len(data)} 字节，超过 {MAX_INFLATED_BYTES} 字节的上限")
    return data, None


@app.api_route("/events", methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"])
async def events(request: Request) -> JSONResponse:
    # ① 方法
    if request.method != "POST":
        return fail(405, "只接受 POST")

    # ② 大小（先看 Content-Length，省掉把大请求读进内存；再看真实字节）
    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > MAX_BODY_BYTES:
        return fail(413, f"请求体 {declared} 字节，超过 {MAX_BODY_BYTES} 字节的上限")
    body = await request.body()
    if len(body) > MAX_BODY_BYTES:
        return fail(413, f"请求体 {len(body)} 字节，超过 {MAX_BODY_BYTES} 字节的上限")

    encoding = (request.headers.get("content-encoding") or "").strip().lower()
    inflated: bytes | None = None
    if encoding in {"gzip", "x-gzip"}:
        inflated, error = inflate(body)
        if error is not None:
            return error
        data = inflated
    elif encoding in {"", "identity"}:
        data = body
    else:
        return fail(400, f"不支持的 Content-Encoding：{encoding}（只认 gzip）")

    # ③ 签名
    if not signature_ok(body, inflated, request.headers.get("x-sig", "")):
        ip = client_ip(request)
        log.warning("签名不对 ip=%s bytes=%s encoding=%s", ip, len(body), encoding or "-")
        return fail(401, "签名不对：X-Sig 不是 hmac_sha256(secret, 请求体) 的十六进制")

    # ④ 限流
    ip = client_ip(request)
    allowed, used, retry_after = ratelimit.hit(ip)
    if not allowed:
        return fail(429, f"太快了：单 IP 每分钟最多 {ratelimit.RATE_LIMIT} 次，{retry_after:.0f} 秒后再试",
                    headers={"Retry-After": str(int(retry_after) + 1)})

    # ⑤ 解析
    try:
        payload = json.loads(data)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return fail(400, "请求体不是合法 JSON")

    device_id = ingest.device_of(request.headers.get("x-device"), payload)
    try:
        rows = ingest.parse_batch(payload, device_id)
    except ingest.IngestError as exc:
        log.info("拒收 ip=%s device=%s reason=%s", ip, device_id, exc.message)
        return fail(exc.status, exc.message)

    # ⑥ 入库
    try:
        accepted = await run_in_threadpool(db.insert_events, rows)
    except Exception:  # noqa: BLE001 —— 堆栈只进日志
        log.exception("入库失败 device=%s 条数=%s", device_id, len(rows))
        return fail(503, "数据库暂时写不进去，稍后重试")
    return JSONResponse({"ok": True, "accepted": accepted})


@app.get("/healthz")
async def healthz() -> JSONResponse:
    up = await run_in_threadpool(db.ping)
    if not up:
        return JSONResponse({"ok": False, "db": "down"}, status_code=503)
    return JSONResponse({"ok": True, "db": "up"})


# ── 只读接口：给值班台 ──────────────────────────────────────────────────
#
# 后台跑在 host 网络上，够不着 track-db（那个库没发布端口）。所以读也走 HTTP：
# 一把 X-Stats-Token 头，常量时间比较，没配口令就整组关掉（503 而不是放行）。
# 这两个接口只做查询，不写任何东西。

def stats_denied(request: Request) -> JSONResponse | None:
    if not STATS_TOKEN:
        return fail(503, "没配 STATS_TOKEN，/stats/* 关着")
    supplied = request.headers.get("x-stats-token", "")
    if not hmac.compare_digest(supplied, STATS_TOKEN):
        return fail(401, "X-Stats-Token 不对")
    return None


@app.get("/stats/overview")
async def stats_overview(request: Request, days: int = 7) -> JSONResponse:
    denied = stats_denied(request)
    if denied is not None:
        return denied
    try:
        data = await run_in_threadpool(stats.overview, days)
    except Exception:  # noqa: BLE001
        log.exception("读概览失败")
        return fail(503, "数据库暂时读不出来")
    return JSONResponse(data)


@app.get("/stats/events")
async def stats_events(request: Request, limit: int = 80, kind: str = "", clean: int = 0) -> JSONResponse:
    denied = stats_denied(request)
    if denied is not None:
        return denied
    try:
        rows = await run_in_threadpool(stats.recent, limit, kind, bool(clean))
    except Exception:  # noqa: BLE001
        log.exception("读事件流失败")
        return fail(503, "数据库暂时读不出来")
    return JSONResponse({"items": rows})

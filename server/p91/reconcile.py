#!/usr/bin/env python3
"""91porn 列表校正服务（relay 常驻，127.0.0.1:8092）。

## 它解决什么

站点在网格页上把**标题和封面随机挂到别的 viewkey 上**：同一个 viewkey 抓两次，
标题有 4/23 会变；点进去看到的是那个 viewkey 的真身，于是「货不对版」。
唯一可信的是详情页 —— 同一个 viewkey 连取三次，标题/封面/直链稳定且互相同号
（thumb/1253049.jpg ↔ mp43/1253049.mp4）。

Java 时代在手机侧做 `/api/reconcile`，24 条并发 4。搬成 spider 之后这条路走不通：
spider 的 `ctx.call('net')` 是**同步桥**，JS 里写 8 个 worker 实际仍然阻塞串行，
一页 24 条要 60 秒以上，直接把前端 30 秒的桥超时打穿（真机实测 45 秒才被看门狗杀掉，
一条结果都没回来）。

所以放到服务器：并发是真的、缓存是所有用户共享的、手机只发一个请求。

## 接口

    GET /p91/reconcile?ids=<viewkey,viewkey,...>   （最多 30 个）

    {"ok": true, "items": [{"id": "...", "title": "...", "cover": "https://..."}]}

失败的单条**不出现**在 items 里 —— 源那边保留原值，一张坏卡不能拖住整页。

## 缓存

12 小时，最多 5000 条，进程内（重启就没了，代价是一轮 3 秒）。同一个 viewkey
的标题基本不变，但片子会下架、投稿人会改标题，留太久就会拿过期真值盖掉正确值。
"""

import json
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse
from urllib.request import Request, urlopen

BASE = "https://901.26702.app"
UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
PORT = 8092

TTL = 12 * 3600
LIMIT = 5000
WORKERS = 8
TIMEOUT = 12
MAX_IDS = 30

# 同一个 viewkey 连取三次标题/封面稳定，但**不带游客标记**时站点会给随机一部片。
# 兜底 ga=1 实测同样管用（和手机侧 FALLBACK_GUEST 用的是同一个值）。
COOKIE = "ga=1"

RE_VIEWKEY = re.compile(r"^[0-9a-zA-Z]+$")
RE_H4 = re.compile(r"<h4[^>]*>(.*?)</h4>", re.S)
RE_TITLE = re.compile(r"<title>(.*?)</title>", re.S)
RE_POSTER = re.compile(r'poster="([^"]+)"')

_cache = {}
_cache_lock = threading.Lock()


def strip_tags(raw):
    text = re.sub(r"<[^>]+>", " ", raw or "")
    return re.sub(r"\s+", " ", text.replace("&nbsp;", " ")).strip()


def fetch(vk):
    """取一条详情页的 (标题, 封面)。失败抛异常，由调用方吞掉。"""
    url = "%s/view_video.php?viewkey=%s" % (BASE, vk)
    req = Request(url, headers={
        "User-Agent": UA,
        "Cookie": COOKIE,
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Accept": "text/html,application/xhtml+xml,*/*;q=0.8",
    })
    with urlopen(req, timeout=TIMEOUT) as res:
        html = res.read().decode("utf-8", "replace")

    title = ""
    hit = RE_H4.search(html)
    if hit:
        title = strip_tags(hit.group(1))
    if not title:
        hit = RE_TITLE.search(html)
        if hit:
            title = strip_tags(hit.group(1))
    poster = RE_POSTER.search(html)
    cover = poster.group(1) if poster else ""
    if cover and not cover.startswith("http"):
        cover = BASE + "/" + cover.lstrip("/")
    return title, cover


def resolve(ids):
    now = time.time()
    out = []
    todo = []
    with _cache_lock:
        for vk in ids:
            hit = _cache.get(vk)
            if hit and now - hit[0] < TTL:
                out.append({"id": vk, "title": hit[1], "cover": hit[2]})
            else:
                todo.append(vk)

    if todo:
        with ThreadPoolExecutor(max_workers=WORKERS) as pool:
            futures = [(pool.submit(fetch, vk), vk) for vk in todo]
            for fut, vk in futures:
                try:
                    title, cover = fut.result()
                except Exception as error:          # noqa: BLE001 - 单条失败不影响整批
                    print("fetch %s failed: %s" % (vk, error), file=sys.stderr, flush=True)
                    continue
                if not title and not cover:
                    continue
                with _cache_lock:
                    _cache[vk] = (time.time(), title, cover)
                out.append({"id": vk, "title": title, "cover": cover})

    with _cache_lock:
        if len(_cache) > LIMIT:
            drop = len(_cache) - LIMIT
            for key, _ in sorted(_cache.items(), key=lambda kv: kv[1][0])[:drop]:
                _cache.pop(key, None)
    return out


class Handler(BaseHTTPRequestHandler):
    server_version = "p91-reconcile/1"

    def do_GET(self):                               # noqa: N802 - BaseHTTPRequestHandler 的约定
        parsed = urlparse(self.path)
        if not parsed.path.endswith("/reconcile"):
            self.send_error(404, "only /reconcile")
            return

        raw = parse_qs(parsed.query).get("ids", [""])[0]
        ids = []
        for one in raw.split(","):
            one = one.strip()
            if one and RE_VIEWKEY.match(one) and one not in ids:
                ids.append(one)
        ids = ids[:MAX_IDS]

        began = time.time()
        items = resolve(ids) if ids else []
        body = json.dumps({"ok": True, "items": items}, ensure_ascii=False).encode("utf-8")

        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)
        print("reconcile ids=%d items=%d %.2fs" % (len(ids), len(items), time.time() - began),
              file=sys.stderr, flush=True)

    def log_message(self, fmt, *args):
        pass


def main():
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print("p91 reconcile listening on 127.0.0.1:%d" % PORT, file=sys.stderr, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()

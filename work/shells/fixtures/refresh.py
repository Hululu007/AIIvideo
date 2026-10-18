#!/usr/bin/env python3
"""
刷新 Hanime1Test 用的 fixture。

为什么不是 curl：站点在 Cloudflare 后面，**闸门卡在 TLS 指纹上**。
curl（OpenSSL）无论怎么补浏览器头都是 403；换任意一种浏览器指纹立刻 200。
所以这里用 curl_cffi 的 impersonate 走到 Chrome 那一侧。

    pip install curl_cffi

看到 403 就是指纹没对上（或者站点改了策略），不要去调请求头 —— 没用。
原理（JA3 比对、六种指纹的实测结果）写在 Hanime1Adapter.java 的类注释里。

抓下来的 HTML **不进仓库**（见 .gitignore 末尾的说明）：它们是被抓站点的
真实页面，而这个站是成人内容站。跑测试时现场抓、用完就扔。
"""
from curl_cffi import requests
import sys, pathlib

PAGES = {
    "home.html":         "https://hanime1.me/",
    "genre-1.html":      "https://hanime1.me/search?genre=%E8%A3%8F%E7%95%AA&page=1",
    "genre-2.html":      "https://hanime1.me/search?genre=%E8%A3%8F%E7%95%AA&page=2",
    "query-1.html":      "https://hanime1.me/search?query=RD&page=1",
    "watch-408706.html": "https://hanime1.me/watch?v=408706",
    "watch-408297.html": "https://hanime1.me/watch?v=408297",
    "empty-page.html":   "https://hanime1.me/search?genre=%E8%A3%8F%E7%95%AA&page=9999",
}

def main():
    here = pathlib.Path(__file__).parent
    bad = 0
    for name, url in PAGES.items():
        try:
            r = requests.get(url, impersonate="chrome131", timeout=30)
        except Exception as e:
            print(f"{name:22s} ERR {type(e).__name__}: {str(e)[:70]}")
            bad += 1
            continue
        if r.status_code != 200:
            print(f"{name:22s} HTTP {r.status_code}（Cloudflare 拦了？换个 impersonate 试）")
            bad += 1
            continue
        (here / name).write_text(r.text, encoding="utf-8")
        print(f"{name:22s} 200 {len(r.text):8d}")
    return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main())

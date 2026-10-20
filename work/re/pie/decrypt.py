#!/usr/bin/env python3
"""水果派（pie）加密 HLS 的解密器。

站点把整条 HLS 链都加密了：index.m3u8 和每个 .ts 都是**整文件 AES-128-ECB + PKCS#7**，
key 是常量（见 KEY），App 侧在本地 8286 那个 shelf 服务里现解现发，所以浏览器/播放器
直接拉 CDN 只会拿到密文。

用法：
  # 直接从接口查（需要 work/re/pie/api.py 里的信封实现）
  python3 decrypt.py --film 10451 --out ./out

  # 或者从 filmDetails 里抄下来的 encryption_url
  python3 decrypt.py --url 'https://…/hls/decrypt/index.m3u8' --out ./out --limit 3

产物：
  out/index.m3u8   明文播放列表（段名是相对路径，直接可播）
  out/<seg>.ts     解密后的 TS
  播放：vlc out/index.m3u8
"""

import argparse
import os
import posixpath
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from Crypto.Cipher import AES

# 2026-10-20 从运行中的进程内存里扫出来的（见 docs/2026-10-20-水果派pie逆向与适配.md）
KEY = b"saIZXc4yMvq0Iz56"
UA = "okhttp/4.12.0"


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    return urllib.request.urlopen(req, timeout=30).read()


def decrypt(data: bytes) -> bytes:
    """整文件 AES-128-ECB，末尾 PKCS#7。"""
    if len(data) % 16:
        raise ValueError(f"长度不是 16 的倍数：{len(data)}")
    pt = AES.new(KEY, AES.MODE_ECB).decrypt(data)
    pad = pt[-1]
    if 1 <= pad <= 16 and pt[-pad:] == bytes([pad]) * pad:
        pt = pt[:-pad]
    return pt


def encryption_url_of(film_id: str) -> str:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from api import call  # noqa: E402  —— 信封实现在同目录的 api.py

    data = call("/v2_0/filmDetails", {"film_id": str(film_id)}).get("data")
    if not isinstance(data, list) or not data:
        raise SystemExit(f"filmDetails({film_id}) 没数据")
    url = data[0].get("encryption_url") or ""
    if not url:
        raise SystemExit(f"film {film_id} 没有 encryption_url（多半是付费内容）")
    return url


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--film", help="film_id，走接口查 encryption_url")
    ap.add_argument("--url", help="直接给 encryption_url")
    ap.add_argument("--out", default="./pie-out")
    ap.add_argument("--limit", type=int, default=0, help="只下前 N 段（0 = 全部）")
    ap.add_argument("--playlist-only", action="store_true", help="只解播放列表")
    args = ap.parse_args()

    url = args.url or (encryption_url_of(args.film) if args.film else None)
    if not url:
        raise SystemExit("给 --film 或 --url")

    os.makedirs(args.out, exist_ok=True)
    raw = fetch(url)
    playlist = decrypt(raw).decode("utf-8")
    segs = [ln.strip() for ln in playlist.splitlines() if ln.strip() and not ln.startswith("#")]
    print(f"播放列表 {len(raw)} 字节密文 → {len(segs)} 段")

    with open(os.path.join(args.out, "index.m3u8"), "w", encoding="utf-8") as f:
        f.write(playlist)
    if args.playlist_only:
        print("只写了 index.m3u8（段还是密文，播不了）")
        return

    base = posixpath.dirname(url) + "/"
    todo = segs[: args.limit] if args.limit else segs

    def one(name: str):
        path = os.path.join(args.out, name)
        if os.path.exists(path):
            return name, "skip"
        try:
            data = decrypt(fetch(base + name))
        except Exception as e:  # noqa: BLE001
            return name, f"fail {e}"
        with open(path, "wb") as f:
            f.write(data)
        return name, f"{len(data)}B"

    done = 0
    with ThreadPoolExecutor(max_workers=8) as ex:
        for name, status in ex.map(one, todo):
            done += 1
            print(f"  [{done}/{len(todo)}] {name} {status}")
    print(f"好了：{args.out}（{len(todo)}/{len(segs)} 段）")


if __name__ == "__main__":
    main()

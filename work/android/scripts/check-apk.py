#!/usr/bin/env python3
"""断言 APK 产物完整。CI 和 build.sh verify 共用。

用法：check-apk.py <apk>
退出码：0 通过 / 1 有问题
"""
import sys
import zipfile

REQUIRED = [
    "assets/app.html",
    "assets/app.js",
    "assets/app.css",
    "assets/site-default.json",
    "assets/site-web.js",
    "assets/hot-update-public.pem",
]


def main():
    if len(sys.argv) != 2:
        print("用法: check-apk.py <apk>", file=sys.stderr)
        return 2

    apk = sys.argv[1]
    try:
        z = zipfile.ZipFile(apk)
    except Exception as e:
        print(f"  打不开 {apk}: {e}", file=sys.stderr)
        return 1

    names = z.namelist()
    dex = sorted(n for n in names if n.endswith(".dex"))
    so = [n for n in names if n.startswith("lib/") and n.endswith(".so")]
    assets = [n for n in names if n.startswith("assets/")]

    problems = []
    if not dex:
        problems.append("没有 dex —— 包是空的？")
    if not so:
        problems.append("没有 native 库 —— libvlc 没打进去，播放器会退回网页播放")
    if len(assets) < 20:
        problems.append(f"assets 只有 {len(assets)} 个，明显偏少（前端文件丢了？）")
    for req in REQUIRED:
        if req not in names:
            problems.append(f"缺 {req}")

    if problems:
        for p in problems:
            print(f"  x {p}", file=sys.stderr)
        return 1

    print(f"  dex {len(dex)} 个 / so {len(so)} 个 / assets {len(assets)} 个", file=sys.stderr)
    for n in dex:
        print(f"    {n}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())

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
    # app.js 拆出来的分片。少一个不会编译失败 —— index.html 里那条 <script src>
    # 指向不存在的文件只在运行时 404，页面照常起来，坏的是某个二级页面里的一整块。
    # 所以这三条必须钉在这里，让 check-apk 在出包时就把它拦下来。
    "assets/app-update.js",
    "assets/source-market.js",
    "assets/proxy-panel.js",
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

    # index 里每一条 <script src> 都得真的在包里。
    #
    # 这是拆 app.js 之后最容易犯的错：新写一个分片、在 app.html 里挂上、忘了
    # 把它加进 REQUIRED —— 或者反过来。两种都会编译通过、启动正常，坏的是某个
    # 二级页面里的一整块（点进「网络代理」白屏，控制台 ReferenceError）。
    # 这里直接读包里那份 app.html，把 src 抽出来逐个对账，一次把两种情况都堵住。
    import re
    html = z.read("assets/app.html").decode("utf-8", "replace")
    for src in re.findall(r'<script[^>]+src="([^"]+)"', html):
        if not src.startswith(("http:", "https:", "//")):
            if "assets/" + src not in names:
                problems.append(f"app.html 引用了不存在的 assets/{src}")

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

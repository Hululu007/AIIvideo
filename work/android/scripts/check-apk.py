#!/usr/bin/env python3
"""断言 APK 产物完整。CI 和 build.sh verify 共用。

用法：check-apk.py <apk>
退出码：0 通过 / 1 有问题
"""
import sys
import zipfile

# 包里的 JS 必须能在 ES2017 引擎上解析。见下面 assert_es2017 的注释。
import os
import shutil
import subprocess
import tempfile

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
    # 未捕获异常的钩子。漏了它页面照常跑、一个字都不报错，只是所有 JS 异常
    # 从此静默消失 —— 而「静默消失」正是 2.0.22 花一整版去修的那类故障。
    # app.html 里那条 <script src> 的对账已经能覆盖，这里再显式钉一条。
    "assets/js-errors.js",
]


def assert_es2017(z, problems):
    """包里的每一个 .js 都得能被 ES2017 引擎解析。

    这是 2.0.24 那道「旧 WebView 白屏」的终点断言。

    白屏的成因是 `?.`（Chrome 80）和 `||=`（Chrome 85）出现在源码里，而
    minSdk 26 的出厂 WebView 是 Chrome 58 —— 脚本在解析阶段就死，`app.js`
    一行都没执行过，用户看到一片白。线上那两台设备除了 `app_open` 之外
    一个业务事件都没有，就是这么来的。

    降级由 Gradle 的 `transpileAssets` 做，它自己也断言一遍输出。这里再断言
    一次，是因为**两件事只有这里能证明**：

      1. sourceSets 真的接上了生成目录（接错就会把没降级的那份打进包，
         而 transpileAssets 会照常通过 —— 它验的是自己产出的目录）；
      2. 打进去的字节就是解析得动的字节，不是中间某个环节留下的旧文件。

    验字节而不是验目录，和上面「app.html 引用的每个 src 都要在包里」同一个理由：
    构建成功、启动也正常，坏的是某类设备上的整个界面。
    """
    js = sorted(n for n in z.namelist() if n.startswith("assets/") and n.endswith(".js"))
    if not js:
        problems.append("包里没有 assets/*.js")
        return

    checker = os.path.join(os.path.dirname(os.path.abspath(__file__)), "js-target-check.js")
    if not os.path.exists(checker):
        problems.append(f"缺 {checker}，无法断言包里的 JS 是否 ES2017 可解析")
        return

    tmp = tempfile.mkdtemp(prefix="apk-js-")
    try:
        for name in js:
            dest = os.path.join(tmp, os.path.relpath(name, "assets"))
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as handle:
                handle.write(z.read(name))
        try:
            # node 缺失是硬失败，不降级成警告 —— 「验不了」和「验过了」不能长得一样。
            done = subprocess.run(["node", checker, tmp, "--quiet"],
                                  capture_output=True, text=True)
        except FileNotFoundError:
            problems.append("缺 node，无法断言包里的 JS 是否 ES2017 可解析（transpileAssets 也依赖它）")
            return
        if done.returncode != 0:
            tail = [line for line in (done.stderr or done.stdout).strip().splitlines() if line.strip()]
            problems.append("包里的 JS 超过 ES2017，旧 WebView 会白屏：\n    "
                            + "\n    ".join(tail[-8:]))
        else:
            print(f"  {len(js)} 个 JS 全部 ES2017 可解析", file=sys.stderr)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


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

    assert_es2017(z, problems)
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

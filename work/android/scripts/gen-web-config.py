#!/usr/bin/env python3
"""
从 site-default.json 生成 assets/site-web.js。

为什么需要这一步：配置只能有一份（site-default.json，Java 的 SiteProfile 直接读它），
但浏览器读不了 JSON 文件 —— 页面 CSP 是 connect-src 'none'（切热更新时收紧的），
fetch 一个本地 json 会被挡掉。

所以构建时把它包一层 window.HG_SITE = {...} 生成成 .js，用 <script> 载入。
生成物不进补丁集，和 classes5.dex 一样属于派生产物。

用法：gen-web-config.py <site-default.json> <输出的 site-web.js>
"""
import json
import sys


def main():
    if len(sys.argv) != 3:
        print("用法: gen-web-config.py <site-default.json> <site-web.js>", file=sys.stderr)
        return 2

    src, dst = sys.argv[1], sys.argv[2]
    with open(src, encoding='utf-8') as fh:
        conf = json.load(fh)

    web = dict(conf.get('web') or {})
    # origin 在 JSON 里是顶层（Java 的 SiteProfile 按顶层键读），
    # 但前端也要用，合并进去省得两边各写一份。
    web['origin'] = conf.get('origin', '')

    body = json.dumps(web, ensure_ascii=False, indent=2, sort_keys=False)
    # 只做缩进对齐，不用 js 语法糖 —— 生成的这份要能被任何工具直接读
    body = '\n'.join('  ' + line for line in body.split('\n'))

    header = (
        "/* 自动生成，不要手改 —— 改 site-default.json 然后重新打包。\n"
        " * 生成器：work/shells/gen-web-config.py\n"
        " * 浏览器读不了 JSON（页面 CSP 是 connect-src 'none'），所以包一层赋值。\n"
        " */\n"
    )
    with open(dst, 'w', encoding='utf-8') as fh:
        fh.write(header + 'window.HG_SITE = ' + body.lstrip() + ';\n')

    print(f"  ok  {dst}  ({len(open(dst, encoding='utf-8').read().encode())} 字节)")
    return 0


if __name__ == '__main__':
    sys.exit(main())

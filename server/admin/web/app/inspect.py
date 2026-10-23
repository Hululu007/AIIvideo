"""编辑器右边栏那份"这份文件到底是什么"。

只做静态分析：不联网、不跑 js。目的是让人在按保存之前就知道哪里不对。
"""
from __future__ import annotations

import json
import re
from typing import Any

CAPABILITIES = ("home", "category", "search", "detail", "play", "tags", "tag-list")


def _host(url: str) -> str:
    raw = str(url or "")
    if "://" in raw:
        return raw.split("://", 1)[1].split("/", 1)[0]
    return raw.split("/", 1)[0]


def _json_body(rel: str, text: str) -> dict[str, Any]:
    """返回 {ok, error, kind, facts, notes}。"""
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        return {
            "ok": False,
            "error": f"第 {exc.lineno} 行第 {exc.colno} 列 —— {exc.msg}",
            "facts": [("行数", str(text.count("\n") + 1))],
            "notes": ["改完再点保存；也可以直接回滚到下面某一版"],
        }
    if not isinstance(data, dict):
        return {"ok": False, "error": "顶层不是对象", "facts": [], "notes": []}

    facts: list[tuple[str, str]] = []
    notes: list[str] = []
    web = data.get("web") or {}

    if isinstance(data.get("sources"), list):
        entries = [item for item in data["sources"] if isinstance(item, dict)]
        facts.append(("条目", str(len(entries))))
        names = [str(item.get("name") or "") for item in entries]
        dupes = {name for name in names if names.count(name) > 1 and name}
        hosts = {_host(item.get("url", "")) for item in entries}
        facts.append(("指向", f"{len(hosts)} 个主机"))
        if dupes:
            notes.append("有重名条目：" + "、".join(sorted(dupes)))
        bad = [str(item.get("name") or "?") for item in entries if not str(item.get("url", "")).startswith("https://")]
        if bad:
            notes.append("这些条目的 url 不是 https：" + "、".join(bad[:4]))
        if data.get("updated"):
            facts.append(("updated", str(data["updated"])))
        notes.append("清单里写的是可下载的配置地址，用户端粘过去才认；改名等于改用户在列表里看到的名字")
        return {"ok": not dupes and not bad, "error": "", "facts": facts, "notes": notes}

    adapter = data.get("adapter") or ""
    api = data.get("api") or ""
    origin = data.get("origin") or ""
    spider = web.get("spider") if isinstance(web, dict) else ""
    if adapter:
        facts.append(("adapter", str(adapter)))
    if origin:
        facts.append(("站点", _host(origin)))
    if api:
        facts.append(("api", _host(api)))
    if spider:
        facts.append(("spider", str(spider).rsplit("/", 1)[-1]))
    categories = web.get("categories") if isinstance(web, dict) else None
    if isinstance(categories, dict):
        facts.append(("分类", str(len(categories))))
    tabs = ((web.get("ui") or {}).get("tabs") if isinstance(web.get("ui"), dict) else None)
    if isinstance(tabs, list):
        facts.append(("首页 tab", str(len(tabs))))
    cover = data.get("cover")
    if isinstance(cover, dict):
        facts.append(("封面", str(cover.get("mode") or "plain")))
        hosts = cover.get("allowedHosts") or []
        if hosts:
            facts.append(("封面域名", "、".join(str(host) for host in hosts[:3])))
    proxies = data.get("proxies") or data.get("proxy")
    if proxies:
        facts.append(("代理", "配了"))
    for label, key in (("headers", "headers"), ("hosts", "hosts")):
        if isinstance(data.get(key), dict):
            facts.append((label, f"{len(data[key])} 条"))

    if not (adapter or api or origin or spider):
        notes.append("没有 adapter / api / origin / web.spider，App 认不出这是什么源")
    if adapter and adapter not in {"cms", "openlist", "custom"}:
        notes.append(f"adapter「{adapter}」是站点专用适配器，换站点要一起换")
    return {"ok": bool(adapter or api or origin or spider), "error": "", "facts": facts, "notes": notes}


def _js_body(text: str) -> dict[str, Any]:
    lines = text.count("\n") + 1
    facts: list[tuple[str, str]] = [("行数", str(lines)), ("字节", str(len(text.encode("utf-8"))))]
    notes: list[str] = []
    has_site = bool(re.search(r"__spiderSite\s*=", text))
    facts.append(("导出 __spiderSite", "有" if has_site else "没有"))
    if not has_site:
        notes.append("找不到 __spiderSite —— 这份 js 装上去 App 跑不起来")
    found = [name for name in CAPABILITIES if re.search(rf"\b{name}\s*\(", text)]
    if found:
        facts.append(("能力", " ".join(found)))
    missing = [name for name in ("home", "detail", "play") if name not in found]
    if missing:
        notes.append("没找到这些必备能力：" + " ".join(missing))
    if re.search(r"\beval\s*\(", text):
        notes.append("里面有 eval()，装上去前先看清楚")
    notes.append("js 不做语法校验（服务器上没有 node）—— 改完在 App 里点一次就知道")
    return {"ok": has_site, "error": "", "facts": facts, "notes": notes}


def describe(rel: str, text: str) -> dict[str, Any]:
    if rel.endswith(".js"):
        info = _js_body(text)
    else:
        info = _json_body(rel, text)
    info["bytes"] = len(text.encode("utf-8"))
    info["lines"] = text.count("\n") + 1
    return info

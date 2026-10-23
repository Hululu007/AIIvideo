"""把 CFG_DIR 里的两代配置摊平成一份源清单。只读。

两代：
  老一代   顶层 `<id>.json` —— App 按 adapter/api 走 REST
  新一代   `spiders/<id>.json` + `spiders/<id>.js` —— App 跑 js

后台的活儿是「哪个源该动手」，所以这里算的是**可静态判定**的东西：
配置能不能解析、js 在不在、发布没发布、市场条目指向的文件在不在。
真正去连站点验证是另一条路（P1 的 checks 表），这里不联网。
"""
from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any, Optional

from .config import CFG_DIR, PUBLIC_BASE

SPIDER_DIR_NAME = "spiders"
SPIDER_DIR = CFG_DIR / SPIDER_DIR_NAME

MARKET_SPIDER = "market-spider.json"
MARKET_LEGACY = "market.json"
# 这几个是清单本身，不是源；列表页要把它们摘出去
LIST_FILES = {MARKET_SPIDER, MARKET_LEGACY, "sources.json"}

# 描述里这些词是"这个源的脾气"，摘出来当标签，省得每次读整段
QUIRKS = (
    ("需代理", "需代理"),
    ("盾后", "盾后"),
    ("Cloudflare", "盾后"),
    ("AES", "AES"),
    ("wasm", "wasm"),
    ("签名", "签名"),
    ("服务端渲染", "直连"),
)

LAMP_TEXT = {
    "live": "就绪",
    "warn": "对不上",
    "dead": "坏了",
    "idle": "未发布",
}


def _mtime(path: Path) -> str:
    return time.strftime("%m-%d %H:%M", time.localtime(path.stat().st_mtime))


def _load(path: Path) -> tuple[Optional[dict], str]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return None, f"第 {exc.lineno} 行第 {exc.colno} 列：{exc.msg}"
    except OSError as exc:
        return None, str(exc)
    if not isinstance(data, dict):
        return None, "顶层不是对象"
    return data, ""


def _public(rel: str) -> str:
    return f"{PUBLIC_BASE}/{rel}" if PUBLIC_BASE else f"/{rel}"


def _quirks(desc: str) -> list[str]:
    found: list[str] = []
    for needle, label in QUIRKS:
        if needle in (desc or "") and label not in found:
            found.append(label)
    return found


def _js_name_of(config: dict, fallback: str) -> str:
    """web.spider 可能是完整 URL，也可能只是个名字（yhdm-spider.json 就写着 "yhdm"）。"""
    raw = str(((config.get("web") or {}).get("spider")) or "").strip()
    if not raw:
        return ""
    if "/" in raw:
        return raw.rsplit("/", 1)[-1]
    return raw if raw.endswith(".js") else f"{raw}.js"


def market_entries() -> tuple[list[dict], str]:
    """读完在用的清单，顺带核对每条指向的文件在不在。"""
    path = CFG_DIR / MARKET_SPIDER
    if not path.exists():
        return [], "清单文件还没建"
    data, error = _load(path)
    if data is None:
        return [], f"{MARKET_SPIDER} 解析失败 —— {error}"
    items = []
    for entry in data.get("sources") or []:
        if not isinstance(entry, dict):
            continue
        url = str(entry.get("url") or "")
        target = url.rsplit("/", 1)[-1] if "/" in url else url
        file = (SPIDER_DIR / target) if target else None
        items.append({
            "name": str(entry.get("name") or ""),
            "desc": str(entry.get("desc") or ""),
            "url": url,
            "target": target,
            "exists": bool(file and file.exists()),
            "entry": entry,
        })
    return items, ""


def _entry_for(entries: list[dict], config_rel: str) -> Optional[dict]:
    needle = config_rel.rsplit("/", 1)[-1]
    for entry in entries:
        if entry["target"] == needle:
            return entry
    return None


def _spider_items(entries: list[dict]) -> list[dict]:
    items: list[dict] = []
    if not SPIDER_DIR.is_dir():
        return items
    for path in sorted(SPIDER_DIR.glob("*.json")):
        rel = f"{SPIDER_DIR_NAME}/{path.name}"
        data, error = _load(path)
        entry = _entry_for(entries, rel)
        spider_id = path.stem
        js_name = _js_name_of(data or {}, spider_id)
        js_path = SPIDER_DIR / js_name if js_name else None
        js_exists = bool(js_path and js_path.is_file())
        web = (data or {}).get("web") or {}
        desc = entry["desc"] if entry else str((data or {}).get("_note") or "")[:80]
        lamp, reason = "live", "配置能解析，js 在位，已发布"
        if error:
            lamp, reason = "dead", f"配置解析失败 —— {error}"
        elif not js_name:
            lamp, reason = "dead", "web.spider 没写，App 装上去也不知道跑哪份 js"
        elif not js_exists:
            lamp, reason = "dead", f"配置里指着 {js_name}，目录里没有这份文件"
        elif not entry:
            lamp, reason = "idle", "本地有这份源，但没写进清单，用户看不到"
        items.append({
            "id": spider_id,
            "name": (entry or {}).get("name") or spider_id,
            "desc": desc,
            "quirks": _quirks(desc),
            "config": rel,
            "js": f"{SPIDER_DIR_NAME}/{js_name}" if js_exists else "",
            "js_name": js_name,
            "js_size": js_path.stat().st_size if js_exists else 0,
            "size": path.stat().st_size,
            "mtime": _mtime(path),
            "js_mtime": _mtime(js_path) if js_exists else "",
            "categories": len(web.get("categories") or {}),
            "host": str((data or {}).get("origin") or web.get("api") or ""),
            "published": bool(entry),
            "url": _public(rel),
            "lamp": lamp,
            "reason": reason,
            "kind": "spider",
        })
    return items


def _legacy_items() -> list[dict]:
    items: list[dict] = []
    if not CFG_DIR.is_dir():
        return items
    for path in sorted(CFG_DIR.glob("*.json")):
        if path.name in LIST_FILES:
            continue
        data, error = _load(path)
        config = data or {}
        web = config.get("web") or {}
        lamp, reason = "live", "老一代配置，App 按 adapter / api 直接取"
        if error:
            lamp, reason = "dead", f"配置解析失败 —— {error}"
        elif not any(config.get(key) for key in ("adapter", "api", "origin", "web")):
            lamp, reason = "dead", "没有 adapter / api / origin，App 认不出这是什么源"
        items.append({
            "id": path.stem,
            "name": path.stem,
            "desc": str(config.get("_note") or "")[:80],
            "quirks": [],
            "config": path.name,
            "js": "",
            "js_name": "",
            "js_size": 0,
            "size": path.stat().st_size,
            "mtime": _mtime(path),
            "js_mtime": "",
            "categories": len(web.get("categories") or {}),
            "host": str(config.get("origin") or config.get("api") or web.get("api") or ""),
            "published": False,
            "url": _public(path.name),
            "lamp": lamp,
            "reason": reason,
            "kind": "legacy",
        })
    return items


def _todo(spider: list[dict], legacy: list[dict], entries: list[dict]) -> list[dict]:
    """总览上那句「需要你动手」。只列真的能动手的事。"""
    out: list[dict] = []
    for item in spider + legacy:
        if item["lamp"] in {"dead", "warn"}:
            out.append({
                "kind": "bad",
                "what": f"{item['name']} —— {item['reason']}",
                "where": item["config"],
            })
    for entry in entries:
        if not entry["exists"]:
            out.append({
                "kind": "bad",
                "what": f"清单里「{entry['name']}」指向的 {entry['target'] or '文件'} 不存在",
                "where": MARKET_SPIDER,
            })
    spider_ids = {item["id"] for item in spider}
    for item in legacy:
        if item["id"] in spider_ids:
            out.append({
                "kind": "note",
                "what": f"{item['id']} 在 spiders/ 和顶层各有一份，顶层那份是旧的",
                "where": item["config"],
            })
    return out


def inventory() -> dict:
    """页面要的一切。一次算好，模板不再自己读盘。"""
    entries, market_error = market_entries()
    spider = _spider_items(entries)
    legacy = _legacy_items()
    counts = {
        "spider": len(spider),
        "published": sum(1 for item in spider if item["published"]),
        "live": sum(1 for item in spider if item["lamp"] == "live"),
        "idle": sum(1 for item in spider if item["lamp"] == "idle"),
        "warn": sum(1 for item in spider if item["lamp"] == "warn"),
        "dead": sum(1 for item in spider + legacy if item["lamp"] == "dead"),
        "legacy": len(legacy),
        "entries": len(entries),
    }
    return {
        "spider": spider,
        "legacy": legacy,
        "entries": entries,
        "counts": counts,
        "todo": _todo(spider, legacy, entries),
        "market_error": market_error,
    }


def public_url(rel: str) -> str:
    """配置在公网上的地址。清单条目就写这个。"""
    return _public(rel)

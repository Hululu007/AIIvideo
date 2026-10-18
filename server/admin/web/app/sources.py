"""源配置与市场清单的读写。

约束：只碰 CFG_DIR 下的 *.json，文件名不允许带目录分隔符。每次写入
① 结构校验 ② 原子替换 ③ 留一份带时间戳的备份。备份是给人用的回滚点，
不是版本系统 —— 真要追溯就看 audit 表。
"""
from __future__ import annotations

import json
import os
import re
import time
from pathlib import Path
from typing import Any

from .config import CFG_DIR, DATA_DIR, READONLY_FILES, SOURCE_SUFFIX

NAME_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
BACKUP_PREFIX = ".bak-"
# 备份**不放在 CFG_DIR**：那个目录是 Caddy 的站点根，放进去等于把历史配置
# 全部公开可读。挪到 DATA_DIR，跟后台自己的库放在一起。
BACKUP_DIR = DATA_DIR / "backups"


class SourceError(ValueError):
    """结构不合法。message 直接给页面显示。"""


def safe_name(name: str) -> str:
    name = str(name or "").strip()
    if not NAME_RE.match(name) or not name.endswith(SOURCE_SUFFIX):
        raise SourceError("文件名只能包含字母数字点下划线短横线，且以 .json 结尾")
    return name


def path_of(name: str) -> Path:
    return CFG_DIR / safe_name(name)


def list_files() -> list[dict[str, Any]]:
    CFG_DIR.mkdir(parents=True, exist_ok=True)
    items = []
    for entry in sorted(CFG_DIR.glob(f"*{SOURCE_SUFFIX}")):
        if not entry.is_file():
            continue
        name = entry.name
        stat = entry.stat()
        info: dict[str, Any] = {
            "name": name,
            "size": stat.st_size,
            "mtime": time.strftime("%Y-%m-%d %H:%M", time.localtime(stat.st_mtime)),
            "readonly": name in READONLY_FILES,
            "kind": "market" if name == "market.json" else "source",
            "adapter": "",
            "origin": "",
            "api": "",
            "error": "",
        }
        try:
            data = json.loads(entry.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                info["adapter"] = str(data.get("adapter") or "")
                info["origin"] = str(data.get("origin") or "")
                info["api"] = str(data.get("api") or "")
        except Exception as exc:  # 文件坏了也要能列出来、能进编辑页去修
            info["error"] = f"JSON 解析失败：{exc}"
        items.append(info)
    return items


def read_text(name: str) -> str:
    path = path_of(name)
    if not path.exists():
        raise SourceError("文件不存在")
    return path.read_text(encoding="utf-8")


def validate_source(data: Any) -> None:
    if not isinstance(data, dict):
        raise SourceError("顶层必须是 JSON 对象")
    if not (data.get("adapter") or data.get("api") or data.get("origin")):
        raise SourceError("缺少 adapter / api / origin，App 认不出这是什么源")
    web = data.get("web")
    if web is not None and not isinstance(web, dict):
        raise SourceError("web 必须是对象")
    cover = data.get("cover")
    if cover is not None and not isinstance(cover, dict):
        raise SourceError("cover 必须是对象")
    for field in ("headers", "hosts"):
        if field in data and not isinstance(data[field], dict):
            raise SourceError(f"{field} 必须是对象")


def validate_market(data: Any) -> None:
    if not isinstance(data, dict):
        raise SourceError("顶层必须是 JSON 对象")
    sources = data.get("sources")
    if not isinstance(sources, list) or not sources:
        raise SourceError("sources 必须是非空数组")
    for index, item in enumerate(sources):
        if not isinstance(item, dict):
            raise SourceError(f"第 {index + 1} 条不是对象")
        if not str(item.get("name") or "").strip():
            raise SourceError(f"第 {index + 1} 条缺少 name")
        url = str(item.get("url") or "").strip()
        if not url.startswith("https://"):
            raise SourceError(f"第 {index + 1} 条的 url 必须以 https:// 开头")


def validate_text(name: str, text: str) -> Any:
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise SourceError(f"JSON 语法错误：第 {exc.lineno} 行第 {exc.colno} 列 —— {exc.msg}")
    if name == "market.json":
        validate_market(data)
    else:
        validate_source(data)
    return data


def save_text(name: str, text: str) -> dict[str, Any]:
    """校验 → 备份 → 原子写。返回写入后的摘要。"""
    safe = safe_name(name)
    if safe in READONLY_FILES:
        raise SourceError(f"{safe} 由发布流程生成，后台不直接改")
    data = validate_text(safe, text)
    pretty = json.dumps(data, ensure_ascii=False, indent=2) + "\n"

    CFG_DIR.mkdir(parents=True, exist_ok=True)
    target = CFG_DIR / safe
    if target.exists():
        _snapshot(target)

    tmp = target.with_name(target.name + ".tmp")
    tmp.write_text(pretty, encoding="utf-8")
    os.replace(tmp, target)
    return {"name": safe, "bytes": len(pretty.encode("utf-8")), "sources": len(data.get("sources", [])) if safe == "market.json" else None}


def _backup_dir() -> "Path":
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    return BACKUP_DIR


def _snapshot(target: Path) -> None:
    stem = target.stem
    stamp = time.strftime("%Y%m%d-%H%M%S")
    dest = _backup_dir() / f"{stem}{BACKUP_PREFIX}{stamp}"
    dest.write_bytes(target.read_bytes())


def backups(name: str) -> list[dict[str, str]]:
    safe = safe_name(name)
    stem = safe[: -len(SOURCE_SUFFIX)]
    result = []
    for entry in sorted(_backup_dir().glob(stem + BACKUP_PREFIX + "*"), reverse=True)[:20]:
        stat = entry.stat()
        result.append({"path": entry.name, "size": stat.st_size,
                       "mtime": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(stat.st_mtime)),
                       "stamp": entry.name.split(BACKUP_PREFIX, 1)[1]})
    return result


def restore(name: str, stamp: str) -> dict[str, Any]:
    safe = safe_name(name)
    if not re.match(r"^\d{8}-\d{6}$", stamp or ""):
        raise SourceError("时间戳格式不对")
    stem = safe[: -len(SOURCE_SUFFIX)]
    backup = _backup_dir() / f"{stem}{BACKUP_PREFIX}{stamp}"
    if not backup.exists():
        raise SourceError("这份备份不存在")
    text = backup.read_text(encoding="utf-8")
    data = validate_text(safe, text)
    pretty = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    target = CFG_DIR / safe
    if target.exists():
        _snapshot(target)
    tmp = target.with_name(target.name + ".tmp")
    tmp.write_text(pretty, encoding="utf-8")
    os.replace(tmp, target)
    return {"name": safe, "bytes": len(pretty.encode("utf-8")), "restored_from": stamp}


def create(name: str, text: str) -> dict[str, Any]:
    safe = safe_name(name)
    if (CFG_DIR / safe).exists():
        raise SourceError("同名文件已存在，换一个名字")
    return save_text(safe, text)


def delete(name: str) -> None:
    safe = safe_name(name)
    if safe in {"market.json"} | READONLY_FILES:
        raise SourceError(f"{safe} 不允许删除")
    target = CFG_DIR / safe
    if not target.exists():
        raise SourceError("文件不存在")
    _snapshot(target)
    target.unlink()

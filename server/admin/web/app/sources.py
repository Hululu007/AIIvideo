"""源配置的读写。

范围：CFG_DIR 顶层和 `spiders/` 子目录，后缀只认 `.json` `.js`。
不允许 `..`、不允许绝对路径、不允许其他目录 —— 后台的手只伸到这两层。

每次写：① 校验 ② 原子替换 ③ 前一份留成备份。备份**不进 CFG_DIR**（那是
Caddy 的站点根，放进去等于把历史配置公开），统一落 DATA_DIR/backups 下，
按目录镜像存放，免得顶层的 `agedm.json` 和 `spiders/agedm.json` 撞名。
"""
from __future__ import annotations

import json
import os
import re
import time
from pathlib import Path
from typing import Any

from .config import CFG_DIR, DATA_DIR, READONLY_FILES

NAME_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
BACKUP_PREFIX = ".bak-"
BACKUP_DIR = DATA_DIR / "backups"
MAX_BYTES = 512 * 1024
SUFFIXES = (".json", ".js")
ALLOWED_DIRS = ("", "spiders")


class SourceError(ValueError):
    """结构不合法。message 直接给页面显示。"""


def safe_rel(name: str) -> str:
    """把页面传进来的名字规范成 `spiders/x.json` 这种相对路径。"""
    raw = str(name or "").strip().replace("\\", "/")
    if not raw or raw.startswith("/") or ".." in raw:
        raise SourceError("文件名不合法")
    parts = raw.split("/")
    if len(parts) == 1:
        directory, filename = "", parts[0]
    elif len(parts) == 2:
        directory, filename = parts[0], parts[1]
    else:
        raise SourceError("只支持顶层和 spiders/ 这两层")
    if directory not in ALLOWED_DIRS:
        raise SourceError(f"不认这个目录：{directory}")
    if not NAME_RE.match(filename) or "/" in filename:
        raise SourceError("文件名只能包含字母数字点下划线短横线")
    if not filename.endswith(SUFFIXES):
        raise SourceError("后缀只认 .json 和 .js")
    rel = f"{directory}/{filename}" if directory else filename
    if rel in READONLY_FILES:
        raise SourceError(f"{rel} 由发布流程生成，后台不直接改")
    return rel


def path_of(name: str) -> Path:
    return CFG_DIR / safe_rel(name)


def is_json(rel: str) -> bool:
    return rel.endswith(".json")


def read_text(name: str) -> str:
    path = path_of(name)
    if not path.exists():
        raise SourceError("文件不存在")
    return path.read_text(encoding="utf-8")


def validate_source(data: Any) -> None:
    if not isinstance(data, dict):
        raise SourceError("顶层必须是 JSON 对象")
    web = data.get("web")
    named = any(data.get(key) for key in ("adapter", "api", "origin"))
    if web is None and not named:
        raise SourceError("缺少 adapter / api / origin / web.spider —— App 认不出这是什么源")
    if web is not None:
        if not isinstance(web, dict):
            raise SourceError("web 必须是对象")
        if not named and not web.get("spider"):
            raise SourceError("有 web 但里面没有 spider，App 装上也跑不了")
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
    if len(text.encode("utf-8")) > MAX_BYTES:
        raise SourceError(f"文件超过 {MAX_BYTES // 1024} KB，后台不接这么大的内容")
    if not is_json(name):
        if not text.strip():
            raise SourceError("内容是空的")
        return None
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise SourceError(f"第 {exc.lineno} 行第 {exc.colno} 列：{exc.msg}")
    if Path(name).name in {"market.json", "market-spider.json"}:
        validate_market(data)
    else:
        validate_source(data)
    return data


def _backup_folder(rel: str) -> Path:
    directory = rel.rsplit("/", 1)[0] if "/" in rel else ""
    folder = BACKUP_DIR / directory if directory else BACKUP_DIR
    folder.mkdir(parents=True, exist_ok=True)
    return folder


def _snapshot(rel: str) -> None:
    source = CFG_DIR / rel
    stem = Path(rel).stem
    stamp = time.strftime("%Y%m%d-%H%M%S")
    dest = _backup_folder(rel) / f"{stem}{BACKUP_PREFIX}{stamp}"
    dest.write_bytes(source.read_bytes())


def _write(rel: str, text: str) -> int:
    payload = text.encode("utf-8")
    target = CFG_DIR / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(target.name + ".tmp")
    tmp.write_bytes(payload)
    os.replace(tmp, target)
    return len(payload)


def save_text(name: str, text: str) -> dict[str, Any]:
    """校验 → 备份 → 原子写。"""
    rel = safe_rel(name)
    data = validate_text(rel, text)
    if is_json(rel):
        pretty = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    else:
        pretty = text if text.endswith("\n") else text + "\n"
    target = CFG_DIR / rel
    if target.exists():
        _snapshot(rel)
    size = _write(rel, pretty)
    entries = len(data.get("sources", [])) if is_json(rel) and isinstance(data, dict) else None
    return {"name": rel, "bytes": size, "entries": entries}


def backups(name: str) -> list[dict[str, str]]:
    rel = safe_rel(name)
    stem = Path(rel).stem
    folder = _backup_folder(rel)
    result = []
    for entry in sorted(folder.glob(stem + BACKUP_PREFIX + "*"), reverse=True)[:20]:
        stat = entry.stat()
        result.append({
            "path": entry.name,
            "size": stat.st_size,
            "mtime": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(stat.st_mtime)),
            "stamp": entry.name.split(BACKUP_PREFIX, 1)[1],
        })
    return result


def _backup_path(rel: str, stamp: str) -> Path:
    if not re.match(r"^\d{8}-\d{6}$", stamp or ""):
        raise SourceError("时间戳格式不对")
    stem = Path(rel).stem
    backup = _backup_folder(rel) / f"{stem}{BACKUP_PREFIX}{stamp}"
    if not backup.exists():
        raise SourceError("这份备份不存在")
    return backup


def restore(name: str, stamp: str) -> dict[str, Any]:
    rel = safe_rel(name)
    text = _backup_path(rel, stamp).read_text(encoding="utf-8")
    data = validate_text(rel, text)
    if is_json(rel):
        pretty = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    else:
        pretty = text if text.endswith("\n") else text + "\n"
    target = CFG_DIR / rel
    if target.exists():
        _snapshot(rel)
    size = _write(rel, pretty)
    return {"name": rel, "bytes": size, "restored_from": stamp}


def create(name: str, text: str) -> dict[str, Any]:
    rel = safe_rel(name)
    if (CFG_DIR / rel).exists():
        raise SourceError("同名文件已存在，换一个名字")
    return save_text(rel, text)


def delete(name: str) -> None:
    rel = safe_rel(name)
    if rel in {"market.json", "market-spider.json"}:
        raise SourceError(f"{rel} 是清单，不允许删")
    target = CFG_DIR / rel
    if not target.exists():
        raise SourceError("文件不存在")
    _snapshot(rel)
    target.unlink()

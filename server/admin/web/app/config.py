"""环境与路径。所有可调的东西都在这一个文件里，别散到各模块去读环境变量。"""
from __future__ import annotations

import os
import secrets
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent


def _bool(name: str, default: bool = False) -> bool:
    return os.getenv(name, "1" if default else "0").strip().lower() in {"1", "true", "yes", "on"}


CFG_DIR = Path(os.getenv("CFG_DIR", "/srv/cfg"))
DATA_DIR = Path(os.getenv("DATA_DIR", "/data"))
DATABASE_URL = os.getenv("DATABASE_URL", "").strip()

ADMIN_USER = os.getenv("ADMIN_USER", "admin").strip() or "admin"
ADMIN_PASSWORD = os.getenv("ADMIN_PASSWORD", "").strip()

# 会话签名/加密密钥。生产必须由环境给；本地起服务时退化成一次性的随机值。
SECRET = os.getenv("SECRET", "").strip() or secrets.token_hex(32)
SESSION_TTL_HOURS = int(os.getenv("SESSION_TTL_HOURS", "12"))
COOKIE_NAME = "admin_session"
PUBLIC_BASE = os.getenv("PUBLIC_BASE", "").rstrip("/")

# 反向代理剥掉的前缀。Caddy 用 handle_path /admin/*，所以线上是 /admin；
# 本地直接起服务时设为空串就能在 / 下调试。
MOUNT_PATH = os.getenv("MOUNT_PATH", "/admin").rstrip("/")

# 源文件白名单：只允许这些后缀，避免后台把 /srv/cfg 写坏
SOURCE_SUFFIX = ".json"
# 这些文件由别的系统写，后台只读不可改
READONLY_FILES = {"hot-update.json"}

DB_PATH = DATA_DIR / "admin.db"

# 埋点服务的只读接口。默认指向回环上的 8092（track 那套发布到 127.0.0.1:8092）。
# 口令和 track 容器里的 STATS_TOKEN 必须是同一个，否则 /stats/* 一律 401。
TRACK_URL = os.getenv("TRACK_URL", "http://127.0.0.1:8092").strip()
TRACK_TOKEN = os.getenv("TRACK_STATS_TOKEN", "").strip()
TRACK_TIMEOUT = float(os.getenv("TRACK_TIMEOUT", "6"))

BASE_DIR.joinpath("templates")  # noqa: B018  —— 早失败：镜像里少拷了模板会直接崩

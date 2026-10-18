"""存储层。

两个数据库，职责分开：
  · SQLite（admin.db）—— 后台自己的账号、会话、审计。掉盘也只影响后台。
  · PostgreSQL（账号库）—— App 用户。这里只碰 users 表，别的表不归后台管。
"""
from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from typing import Iterator

from .config import DATABASE_URL, DB_PATH

SCHEMA = """
CREATE TABLE IF NOT EXISTS admins (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    username    TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at  TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at  TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS sessions (
    token       TEXT PRIMARY KEY,
    admin_id    INTEGER NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    csrf        TEXT NOT NULL,
    created_at  TEXT NOT NULL DEFAULT (datetime('now')),
    expires_at  INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS audit (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    at          TEXT NOT NULL DEFAULT (datetime('now')),
    actor       TEXT NOT NULL,
    action      TEXT NOT NULL,
    target      TEXT NOT NULL DEFAULT '',
    detail      TEXT NOT NULL DEFAULT '',
    ip          TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS audit_at_idx ON audit (id DESC);
"""


def init_sqlite() -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(DB_PATH) as conn:
        conn.executescript(SCHEMA)


@contextmanager
def sqlite() -> Iterator[sqlite3.Connection]:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


@contextmanager
def pg():
    """账号库连接。没配 DATABASE_URL 时直接抛错，让页面给出人话。"""
    import psycopg
    from psycopg.rows import dict_row

    if not DATABASE_URL:
        raise RuntimeError("没有配置 DATABASE_URL，用户管理不可用")
    with psycopg.connect(DATABASE_URL, row_factory=dict_row, connect_timeout=5) as conn:
        yield conn



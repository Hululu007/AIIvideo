"""App 用户管理（账号库 users 表）。

只做后台该做的事：看、查、重置口令、删号。不做「代登录」——
后台不需要拿到用户的会话，也就没有那条风险链。
"""
from __future__ import annotations

from typing import Any

from .db import pg

PAGE_SIZE = 30


def list_users(query: str = "", page: int = 1) -> dict[str, Any]:
    query = (query or "").strip()
    page = max(1, int(page or 1))
    offset = (page - 1) * PAGE_SIZE
    like = f"%{query}%"
    with pg() as conn:
        if query:
            rows = conn.execute(
                "SELECT id, username, email, created_at, updated_at FROM users "
                "WHERE username ILIKE %s OR email ILIKE %s ORDER BY created_at DESC LIMIT %s OFFSET %s",
                (like, like, PAGE_SIZE + 1, offset),
            ).fetchall()
            total = conn.execute(
                "SELECT count(*) AS n FROM users WHERE username ILIKE %s OR email ILIKE %s",
                (like, like),
            ).fetchone()["n"]
        else:
            rows = conn.execute(
                "SELECT id, username, email, created_at, updated_at FROM users "
                "ORDER BY created_at DESC LIMIT %s OFFSET %s",
                (PAGE_SIZE + 1, offset),
            ).fetchall()
            total = conn.execute("SELECT count(*) AS n FROM users").fetchone()["n"]
    has_more = len(rows) > PAGE_SIZE
    users = rows[:PAGE_SIZE]
    for user in users:
        user["created_at"] = user["created_at"].strftime("%Y-%m-%d %H:%M") if user.get("created_at") else ""
        user["updated_at"] = user["updated_at"].strftime("%Y-%m-%d %H:%M") if user.get("updated_at") else ""
    return {"users": users, "total": total, "page": page, "has_more": has_more, "query": query}


def stats() -> dict[str, Any]:
    with pg() as conn:
        users = conn.execute("SELECT count(*) AS n FROM users").fetchone()["n"]
        objects = conn.execute("SELECT count(*) AS n FROM objects").fetchone()["n"]
        sessions = conn.execute("SELECT count(*) AS n FROM sessions").fetchone()["n"]
    return {"users": users, "objects": objects, "sessions": sessions}


def reset_password(user_id: str, new_password: str) -> None:
    from .auth import hash_password  # 同一个 scrypt 口径，账号后端认

    if len(new_password or "") < 8:
        raise ValueError("新口令至少 8 位")
    with pg() as conn:
        row = conn.execute("SELECT id FROM users WHERE id = %s", (user_id,)).fetchone()
        if not row:
            raise ValueError("这个用户不存在")
        conn.execute(
            "UPDATE users SET password_hash = %s, updated_at = now() WHERE id = %s",
            (hash_password(new_password), user_id),
        )
        # 改完口令把会话全踢掉：口令都换了，旧设备不该还挂着
        conn.execute("DELETE FROM sessions WHERE user_id = %s", (user_id,))


def delete_user(user_id: str) -> str:
    with pg() as conn:
        row = conn.execute("SELECT username FROM users WHERE id = %s", (user_id,)).fetchone()
        if not row:
            raise ValueError("这个用户不存在")
        # 收藏/历史挂在 objects 表，按 owner_id 一起清，避免留孤儿行
        conn.execute("DELETE FROM objects WHERE owner_id = %s", (user_id,))
        conn.execute("DELETE FROM users WHERE id = %s", (user_id,))
    return row["username"]

"""后台登录：scrypt 口令、签名 cookie 会话、CSRF。

三层都做，是因为这个面板能被公网访问到（Caddy 的 basicauth 是第一层，
这里是第二层）。口令绝不落明文，cookie 只放随机 token，签名用 itsdangerous。
"""
from __future__ import annotations

import hashlib
import hmac
import secrets
import time
from typing import Optional

from fastapi import HTTPException, Request
from itsdangerous import BadSignature, URLSafeSerializer

from .config import ADMIN_PASSWORD, ADMIN_USER, COOKIE_NAME, SECRET, SESSION_TTL_HOURS
from .db import sqlite

serializer = URLSafeSerializer(SECRET, salt="admin-session")

SCRYPT_N = 2 ** 14


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode("utf-8"), salt=salt, n=SCRYPT_N, r=8, p=1, dklen=32)
    return f"scrypt${SCRYPT_N}${salt.hex()}${digest.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        scheme, n, salt_hex, digest_hex = stored.split("$")
        if scheme != "scrypt":
            return False
        digest = hashlib.scrypt(
            password.encode("utf-8"), salt=bytes.fromhex(salt_hex),
            n=int(n), r=8, p=1, dklen=32,
        )
        return hmac.compare_digest(digest.hex(), digest_hex)
    except Exception:
        return False


def ensure_admin() -> None:
    """首次启动把环境变量里的账号写进库；已存在则不动。"""
    with sqlite() as conn:
        row = conn.execute("SELECT id FROM admins WHERE username = ?", (ADMIN_USER,)).fetchone()
        if row:
            return
        if not ADMIN_PASSWORD:
            # 没有初始口令就生成一个随机串并打到日志里，绝不建成空口令账号
            password = secrets.token_urlsafe(12)
            print(f"[admin] ADMIN_PASSWORD 未设置，临时口令：{password}", flush=True)
        else:
            password = ADMIN_PASSWORD
        conn.execute(
            "INSERT INTO admins(username, password_hash) VALUES (?, ?)",
            (ADMIN_USER, hash_password(password)),
        )


def check_credentials(username: str, password: str) -> Optional[int]:
    with sqlite() as conn:
        row = conn.execute("SELECT id, password_hash FROM admins WHERE username = ?", (username,)).fetchone()
    if row and verify_password(password, row["password_hash"]):
        return int(row["id"])
    return None


def change_password(admin_id: int, new_password: str) -> None:
    with sqlite() as conn:
        conn.execute(
            "UPDATE admins SET password_hash = ?, updated_at = datetime('now') WHERE id = ?",
            (hash_password(new_password), admin_id),
        )


def start_session(admin_id: int) -> tuple[str, str]:
    token = secrets.token_urlsafe(32)
    csrf = secrets.token_urlsafe(24)
    expires = int(time.time()) + SESSION_TTL_HOURS * 3600
    with sqlite() as conn:
        conn.execute(
            "INSERT INTO sessions(token, admin_id, csrf, expires_at) VALUES (?, ?, ?, ?)",
            (token, admin_id, csrf, expires),
        )
    return token, csrf


def end_session(token: str) -> None:
    with sqlite() as conn:
        conn.execute("DELETE FROM sessions WHERE token = ?", (token,))


def _session(token: Optional[str]):
    if not token:
        return None
    with sqlite() as conn:
        row = conn.execute(
            "SELECT s.token, s.csrf, s.expires_at, a.id AS admin_id, a.username "
            "FROM sessions s JOIN admins a ON a.id = s.admin_id WHERE s.token = ?",
            (token,),
        ).fetchone()
    if not row:
        return None
    if int(row["expires_at"]) < int(time.time()):
        end_session(token)
        return None
    return row


def current(request: Request):
    token = request.cookies.get(COOKIE_NAME)
    if not token:
        return None
    try:
        raw = serializer.loads(token)
    except BadSignature:
        return None
    row = _session(raw)
    if not row:
        return None
    return {"admin_id": row["admin_id"], "username": row["username"], "csrf": row["csrf"], "token": raw}


def require(request: Request):
    session = current(request)
    if not session:
        raise HTTPException(status_code=303, detail="login", headers={"Location": "/admin/login"})
    return session


def require_csrf(request: Request, submitted: str) -> None:
    session = require(request)
    if not submitted or not hmac.compare_digest(session["csrf"], submitted):
        raise HTTPException(status_code=400, detail="CSRF 校验失败，刷新页面重试")


def sign_cookie(token: str) -> str:
    return serializer.dumps(token)


def client_ip(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for", "")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else ""

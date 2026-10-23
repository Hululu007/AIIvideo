"""本地开发入口：把账号库换成假数据，模板和样式照真跑。

    CFG_DIR=/tmp/hgadmin/cfg DATA_DIR=/tmp/hgadmin/data MOUNT_PATH= \
    PUBLIC_BASE=https://149.104.21.177.sslip.io ADMIN_USER=admin ADMIN_PASSWORD=devpass123 \
    SECRET=dev /tmp/hgadmin/venv/bin/uvicorn dev.devapp:app --port 8099

只影响本机进程：真身是 app.main:app，这里只是在导入之后把 users 里的几个函数
换成假的，免得为了看个表格在开发机上装 PostgreSQL。
"""
from __future__ import annotations

import datetime
import random

from app import users
from app.main import app  # noqa: F401

NAMES = [
    "cxedon", "minoabc", "book", "motlabb", "shskk", "udhwb dusbwkx", "pikanben",
    "lurker", "nightowl", "zzz", "apple", "yuki", "haru", "kenta", "mio", "rin",
    "sora", "aki", "nagi", "tomo", "ren", "kaito", "hina", "yui", "aoi", "riku",
    "tsuki", "hoshi", "kaze", "umi",
]


def _fake_stats() -> dict:
    return {"users": 189, "objects": 308, "sessions": 200}


def _fake_list(query: str = "", page: int = 1) -> dict:
    page = max(1, int(page or 1))
    names = [name for name in NAMES if not query or query.lower() in name.lower()]
    total = 189 if not query else len(names)
    start = (page - 1) * 30
    rows = []
    rng = random.Random(7)
    for index, name in enumerate(names[start:start + 30]):
        created = datetime.datetime(2026, 10, 22, 17 - index % 12, 48 - index % 40)
        rows.append({
            "id": f"u{start + index:03d}",
            "username": name,
            "email": "" if index % 3 else f"{name}@example.com",
            "created_at": created.strftime("%Y-%m-%d %H:%M"),
            "updated_at": (created if index % 4 else created + datetime.timedelta(hours=rng.randint(1, 30))).strftime("%Y-%m-%d %H:%M"),
        })
    return {"users": rows, "total": total, "page": page, "has_more": start + 30 < total, "query": query}


def _refuse(*_args, **_kwargs):
    raise ValueError("本地开发不写账号库")


users.stats = _fake_stats
users.total_cached = lambda ttl=30.0: "189"
users.list_users = _fake_list
users.reset_password = _refuse
users.delete_user = _refuse

"""环境与常量。所有可调的东西都在这一个文件里，别散到各模块去读环境变量。

改动任何一个上限（条数/字节/速率）都只动这里，别在 main.py 里另写一个数字。
"""
from __future__ import annotations

import os
import secrets
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent

# 自己的库。**不复用账号库**：理由写在 README「数据库选型」那一节。
DATABASE_URL = os.getenv("DATABASE_URL", "").strip()

# App 端算 X-Sig 的那串密钥。生产必须由环境给 —— 没配就退化成一次性随机值，
# 效果是所有请求都 401。这比「没配就放行」安全：宁可挂，不可敞着。
TRACK_SECRET = os.getenv("TRACK_SECRET", "").strip() or secrets.token_hex(32)

# 后台读埋点用的口令。跟 TRACK_SECRET 分开：那把是 App 用来签请求体的，
# 这把只验「你是值班台」。没配就等于关掉 /stats/*（回 503），不会敞着。
STATS_TOKEN = os.getenv("STATS_TOKEN", "").strip()

# ── 请求上限 ────────────────────────────────────────────────────────────
MAX_EVENTS = int(os.getenv("MAX_EVENTS", "200"))            # 单次条数
MAX_EVENT_BYTES = int(os.getenv("MAX_EVENT_BYTES", "4096"))  # 单条序列化后
MAX_BODY_BYTES = int(os.getenv("MAX_BODY_BYTES", str(1024 * 1024)))          # gzip 前
MAX_INFLATED_BYTES = int(os.getenv("MAX_INFLATED_BYTES", str(8 * 1024 * 1024)))  # 解压后

# ── 限流 ────────────────────────────────────────────────────────────────
RATE_LIMIT = int(os.getenv("RATE_LIMIT_PER_MINUTE", "60"))
RATE_WINDOW = 60.0

# ── 留存与聚合 ──────────────────────────────────────────────────────────
RETENTION_DAYS = int(os.getenv("RETENTION_DAYS", "90"))
# 按哪个时区切「一天」。用户都在国内，按 Asia/Shanghai 切出来的日期才对得上
# 运营嘴里的「昨天」；按 UTC 切会让凌晨 0-8 点的事件算到前一天去。
AGG_TZ = os.getenv("AGG_TZ", "Asia/Shanghai").strip() or "Asia/Shanghai"
AGG_AT = os.getenv("AGG_AT", "00:10").strip()  # 本地时间 HH:MM

# 数据库重试：compose 的 depends_on 只保证「容器起来了」，不保证 postgres 收工。
DB_CONNECT_RETRIES = int(os.getenv("DB_CONNECT_RETRIES", "15"))

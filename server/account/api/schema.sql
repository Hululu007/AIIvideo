-- AIIvideo 账号后端 schema。
--
-- 全部用 IF NOT EXISTS，启动时无条件跑一遍 —— 这一层不是迁移系统，
-- 加字段就往后追加一句 alter，别改已有的。
--
-- 为什么对象表是「一张表 + JSONB」而不是每个 Parse 类一张表：
-- 客户端的 ParseObject 类是**运行时**才决定名字的（现在有 UserLibrary 和
-- DualUserLibrary 两个），照着一类一表建，下次客户端加一个类就得改服务端。
-- JSONB 这一侧只有「字段名」是变化的，那本来就不该由数据库来管。

CREATE TABLE IF NOT EXISTS users (
    id            TEXT PRIMARY KEY,
    username      TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    email         TEXT,
    avatar_url    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 账号唯一性用不区分大小写的索引：注册时查的是 lower(username)，
-- 唯一索引必须和它对齐，否则 Alice / alice 能同时存在。
CREATE UNIQUE INDEX IF NOT EXISTS users_username_lower_idx ON users (lower(username));

CREATE TABLE IF NOT EXISTS sessions (
    token      TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS sessions_user_idx ON sessions (user_id);

CREATE TABLE IF NOT EXISTS objects (
    id         TEXT PRIMARY KEY,
    class_name TEXT NOT NULL,
    owner_id   TEXT,
    data       JSONB NOT NULL DEFAULT '{}'::jsonb,
    acl        JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS objects_class_idx ON objects (class_name);
CREATE INDEX IF NOT EXISTS objects_owner_idx ON objects (class_name, owner_id);
-- 观看历史按更新时间倒序取，走这个索引。
CREATE INDEX IF NOT EXISTS objects_updated_idx ON objects (class_name, owner_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS files (
    name         TEXT PRIMARY KEY,
    content      BYTEA NOT NULL,
    content_type TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

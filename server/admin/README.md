# 运维后台（admin）

一个跑在 VPS 上的小后台：维护源配置、市场清单，管理 App 用户。

## 为什么单独一个工程

之前的运维动作全是「本地改 JSON → scp 到 `/srv/cfg`」，改错一个字符 App 里就
是白屏，而且没有回滚记录。这个工程把这些动作收进网页：

- 源清单 / 市场清单在浏览器里编辑，保存前做结构校验
- 每次保存自动留一份 `<文件>.bak-<时间>`，出问题一键回滚到上一版
- 用户列表直连账号库，能重置密码、删除账号
- 所有写操作进审计日志（谁、什么时候、改了什么文件、改前改后的大小）

## 拓扑

```text
浏览器 ──https──> Caddy
                    ├─ /admin/*  （basicauth 先挡一层）
                    │     └─ reverse_proxy 127.0.0.1:8091  (admin-web)
                    │            ├─ /srv/cfg        读写（源与市场清单）
                    │            ├─ admin.db        SQLite：后台账号 / 会话 / 审计
                    │            └─ account-db:5432 PostgreSQL（App 用户，只读写 users 表）
                    └─ /*        /srv/cfg 静态文件（App 实际读取的源）
```

后台自己那层登录 + Caddy 的 basicauth 是两道门，任何一道挡住都进不来。

## 部署

```bash
# 本机
./deploy.sh            # 打包 web/ 推到 relay:/srv/admin 并重启容器

# VPS 上（首次）
cp .env.example .env   # 填 ADMIN_PASSWORD / POSTGRES_PASSWORD / SECRET
docker compose up -d --build
```

## 账号

- 后台账号存 `admin.db`，首次启动用 `.env` 里的 `ADMIN_USER` / `ADMIN_PASSWORD`
  建一个；登录后可在「账号」页改密码。
- App 用户来自账号后端那张 `users` 表，后台不建用户，只管查询与处置。

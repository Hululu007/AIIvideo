# 运维后台（admin）

跑在 VPS 上的小后台：维护源、维护清单、管理 App 用户。界面叫「值班台」，
设计意图写在 [DESIGN.md](DESIGN.md) 里 —— 改样式之前先读那三条硬规则。

## 它管什么

```
/srv/cfg/
  market-spider.json      在用的 spider 清单（后台的「清单」页写这份）
  market.json             旧版 REST 清单（客户端还认，页面上单独一块）
  spiders/<id>.json       14 个 spider 源的配置
  spiders/<id>.js         对应的 js（后台能读、能改）
  hot-update.json         只读，走 GitHub 那条通道
/data/backups/
  <name>.bak-<stamp>      顶层文件的备份
  spiders/<name>.bak-*    spiders 里文件的备份（按目录镜像，不撞名）
```

范围写死在 `app/sources.py`：只认顶层和 `spiders/` 这两层，后缀只认 `.json` `.js`，
`..`、绝对路径、别的目录一律拒。

## 页面

| 路径 | 干什么 |
|---|---|
| `/` | 灯带 + 需要你动手 + 数量 + 最近动作 + 这台机器 |
| `/sources` | 源列表（spider 14 + 老一代 3），搜索、只看要动手的 |
| `/sources/<rel>/edit` | 编辑器：行号、检查栏、备份时间线、⌘S、改动行数 |
| `/market` | 清单：勾选 + 就地改名字和说明 + 写回 `market-spider.json` |
| `/data` | 埋点：规模、每天、源、失败排行、版本机型、事件流（1/7/30/90 天） |
| `/users` | App 用户，搜索、重置口令、删号（走弹层，不用 `prompt`） |
| `/audit` | 审计，最近 300 条 |
| `/account` | 改自己的口令 |

`<rel>` 是相对路径，例如 `spiders/51cg.json` 或 `market.json`。

`/data` 的数不是这个后台自己算的：埋点库一步都没出 track 那套 compose 网络，
后台是 host 网络，够不着它。所以整页读 track 服务的两个只读接口
（`/stats/overview`、`/stats/events`），用 `TRACK_URL` + `TRACK_STATS_TOKEN` 两个环境变量，
后者要和 `/srv/track/.env` 的 `STATS_TOKEN` 完全一致。没配就只显示一句「没配」，
其它页不受影响。

## 拓扑

```text
浏览器 ──https──> Caddy
                    ├─ /admin/*  （basicauth 先挡一层）
                    │     └─ reverse_proxy 127.0.0.1:8091  (admin-web)
                    │            ├─ /srv/cfg        读写（源与清单）
                    │            ├─ admin.db        SQLite：后台账号 / 会话 / 审计
                    │            └─ 127.0.0.1:5432  PostgreSQL（App 用户，只读写 users）
                    └─ /*        /srv/cfg 静态文件（App 实际读取的源）
```

后台自己那层登录 + Caddy 的 basicauth 是两道门，任何一道挡住都进不来。

## 部署

```bash
# 本机（需要 ~/.ssh/config 里配好 relay，用 key 登录）
./deploy.sh
```

Dockerfile 是 `COPY app ./app`，模板和静态文件都烤进镜像 —— **改完必须重建**，
没有「只传文件」那条路。旧镜像在服务器上留了 tag：

```bash
docker tag admin-web:prev-20261023 admin-web:latest && docker compose up -d
```

## 本机开发

不用为了看页面在开发机上装 PostgreSQL：

```bash
cd server/admin/web
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
CFG_DIR=/path/to/cfg-copy DATA_DIR=/tmp/admin-data MOUNT_PATH= \
PUBLIC_BASE=https://example.test ADMIN_USER=admin ADMIN_PASSWORD=devpass123 SECRET=dev \
.venv/bin/uvicorn dev.devapp:app --port 8099
```

`dev/devapp.py` 只把 `users` 里几个函数换成假数据，其余照真跑。`CFG_DIR` 指一份
`/srv/cfg` 的副本，别指生产。

## 账号

- 后台账号存 `admin.db`，首次启动用 `.env` 里的 `ADMIN_USER` / `ADMIN_PASSWORD` 建一个；
  登录后可在「账号」页改。
- App 用户来自账号后端那张 `users` 表，后台不建用户，只管查询与处置。

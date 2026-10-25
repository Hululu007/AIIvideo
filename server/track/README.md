# 埋点 / 崩溃上报（track）

App 批量上报的事件和崩溃进这里。规模：189 个用户 × 每天 200 条 ≈ 4 万行/天 ——
Postgres 一张表就够，不需要队列、不需要采样、不需要 ClickHouse。

需求出处是 `TODO.md` 的「2026-10-23 可观测性 / B 上报管道」。

## 目录

```
server/track/
  web/
    Dockerfile            python:3.12-slim，非 root，监听 0.0.0.0:8092
    requirements.txt      fastapi / uvicorn / psycopg[binary]
    app/
      config.py           所有环境变量与上限都在这一处
      db.py               建表、批量插入、日聚合、过期清理
      ingest.py           请求体 → 入库行（校验与拆列）
      ratelimit.py        单 IP 每分钟的滑动窗口
      jobs.py             每天 00:10 聚合前一天 + 删 90 天前的明细
      main.py             POST /events、GET /healthz
  docker-compose.yml      track-db（postgres:16-alpine）+ track-web
  deploy.sh               rsync 到 relay:/srv/track 并重建
  acceptance.py           契约逐条验收（标准库，不用装依赖）
  tests/                  单元测试（标准库 unittest，不需要库）
  .env.example
```

## 契约

Caddy 侧是 `handle_path /t/* { reverse_proxy 127.0.0.1:8092 }`，前缀被剥掉，
所以服务自己的路由是 `/events` 和 `/healthz`。

```
POST /t/events
Content-Type: application/json
X-Sig: <hex(HMAC-SHA256(secret, 原始请求体))>
X-Device: <device_id>            # 可选，只进 device_id 列和日志
Content-Encoding: gzip           # 可选，支持
```

```json
{"app": {"v": "2.0.19", "vc": 20019, "os": 34, "model": "23113RKC6C", "patch": 0},
 "events": [{"id": "uuid", "at": 1729680000123, "session": "uuid", "kind": "play",
             "name": "play_error", "source": "51cg",
             "props": {"stage": "resolve", "code": "timeout", "ms": 8000}}]}
```

成功 `{"ok": true, "accepted": N}`，N 是**真正新写入**的行数 —— 重发的 event_id 被
去重掉，不计入，所以客户端重发同一批会看到 `accepted: 0`，这是对的。

**一批里有个别坏事件时**：回 200，多两个字段 `rejected` 和 `errors`：

```json
{"ok": true, "accepted": 1, "rejected": 1,
 "errors": [{"index": 2, "reason": "第 2 条的 at 必须是毫秒时间戳",
             "kind": "play", "name": "play_error"}]}
```

`errors` 最多带 5 条（`ingest.MAX_REPORTED_ERRORS`），`rejected` 是总数。这个口径是
2026-10-25 改的：以前一条坏事件让整批回 400，而客户端对 4xx 的处理是「丢掉」——
线上 `kind='play'` 一条都没有，就是每次播放失败都在炸自己的批次。

失败 `{"ok": false, "error": "人话"}` + 400 / 401 / 405 / 413 / 429。堆栈只进容器日志。

**两种拒收，别混**：批级问题（请求体不是对象 / events 不是数组 / 空数组 / 条数超限 /
请求体超字节）才回 4xx，因为那是发请求的人根本不会发；单条问题（字段类型不对、单条超
4KB、`at` 越界）降级进 `rejected`，其余照收。见 `web/app/ingest.py` 顶部那段注释。

**校验顺序：方法 → 大小 → 签名 → 限流 → 解析 → 入库。** 这个次序是攻击面的一部分：
签名排在小请求体之后、限流之前，所以坏签名的扫描流量不消耗限流配额；超大请求不花
CPU 去算 HMAC 和解析 JSON。别调换。

几个实现上的选择，写下来省得后面有人当成 bug：

- **gzip 的签名对哪一串字节**（接 App 的人必读）：客户端签的是**未压缩的 JSON body**，
  先 `X-Sig = hex(hmac_sha256(secret, body))`，再 `gzip(body)` 发出去
  （`ReportQueue.post()` 就是这个顺序）。服务端因此**两种都认**：先拿线上收到的字节验，
  不通过再拿解压后的字节验。多花一次 HMAC，不放松任何东西 —— 两种都算不出来照样 401。
  反过来若哪天客户端改成「先 gzip 再对压缩体签名」，这里不用动。
- **`device_id`**：优先级 每条事件的 `device`（App 端 Report.java 就是这么发的）→
  `X-Device` 头 → 请求顶层的 `device`/`device_id` → `unknown`。列是 `not null`，
  宁可留一个占位值也不丢整条事件。
- **`stage` / `code` / `ms`** 从 `props` 里抽成列（也认事件顶层的同名键），`props`
  原样整份保留 —— 抽列是为了能走索引，不是丢弃。**`code` 收数字**：前端播放失败带的
  是 `video.error.code`（MediaError 枚举 1~4），数字转字符串入库（`_code_text`）。
- **条数上限 413**：200 条是「大小」那一条限制的另一半，所以和 4KB 一样返回 413，
  error 里写明是条数超了，不是字节超了。这是**批级**问题。
- 单条上限按 `json.dumps(event, separators=(",",":"))` 的 UTF-8 字节数算。超了是
  **单条**问题：那一条进 `rejected`，不再 413。App 侧另有 `Report.EVENT_BUDGET = 3800`
  先自己收住（崩溃堆栈按字节裁），两边留了 296 字节余量。
- `at` 限定在 2000~2100 年，防止时钟坏掉的客户端把聚合表切成 1970 年的行。
- **`app` 那组元数据（v/vc/os/model）坏了不拒单条**：它们是装饰字段，一条
  `model` 超长的老客户端不该让整批进不来（`_app_meta` 自己吞异常）。

**`/stats/*` 的「真失败」口径**在 `stats.py` 的 `NOISE_SQL`，把取消类剔出去。它只影响
页面读数，**不动 `events_daily` 的 `errors` 判据**（那边仍是「code 非空」），所以改
`NOISE_SQL` 不用重跑聚合。里面 `InterruptedIOException` 是按 message 判的，因为
`SocketTimeoutException extends InterruptedIOException` —— 按类名判会把真超时洗成取消。

## 表设计（最终形态）

就是 `TODO.md` 那张稿子，多两样：`event_id` 唯一索引（重发去重）、`events_daily`
的主键（聚合可重跑）。建表语句在 `web/app/db.py` 的 `SCHEMA` 里，启动时
`create ... if not exists` 无条件跑一遍 —— 这一层不是迁移系统，加字段往后追加 `alter`。

```sql
create table events (
  id bigserial primary key,
  event_id text not null,          -- 客户端生成的 uuid
  device_id text not null,         -- 安装级 uuid，不采账号
  session_id text,
  at timestamptz not null,         -- 客户端时间（毫秒戳换算）
  received_at timestamptz not null default now(),
  kind text not null, name text not null,
  source_id text, stage text, code text, ms integer,
  props jsonb not null default '{}'::jsonb,
  app_version text, app_vc integer, os integer, model text, patch integer
);
create unique index events_event_id_uidx on events (event_id);
create index events_at_idx on events (at desc);
create index events_kind_name_at_idx on events (kind, name, at desc);
create index events_source_at_idx on events (source_id, at desc);
create index events_device_at_idx on events (device_id, at desc);

create table events_daily (        -- 永久保留
  day date not null, kind text not null, name text not null,
  source_id text not null default '',
  events integer not null, devices integer not null, errors integer not null,
  ms_p50 integer, ms_p95 integer, updated_at timestamptz not null default now(),
  primary key (day, kind, name, source_id)
);
```

`received_at` 和 `at` 分开是有用的：差得多说明客户端离线队列积压了很久，这也是一
条信息。`errors` 的定义是「`code` 列非空的事件数」。

留存：每天 `delete from events where at < now() - interval '90 days'`，一条语句，
不分区不归档 —— 4 万/天 × 90 天 = 360 万行，Vacuum 自己会收拾。

聚合`(day, kind, name, source_id)` 时按 `Asia/Shanghai` 切日（`AGG_TZ` 可改）。
按 UTC 切会把凌晨 0–8 点的事件算进前一天，跟运营嘴里的「昨天」对不上。

定时器是进程里的一个协程，`AGG_AT`（默认 00:10）跑一次；启动时先补跑一遍昨天，
所以容器重启不会漏。聚合逻辑可手动重跑，重跑结果一样：

```bash
docker compose exec web python -m app.jobs              # 昨天
docker compose exec web python -m app.jobs 2026-10-20   # 指定日期重算
docker compose exec web python -m app.jobs --purge      # 只删过期明细
```

## 数据库选型：为什么另起一个 track-db

服务器上已经有 `account-db-1`（postgres:16），复用实例 + 建独立 database `track`
也能跑。我选了**另起一个容器**，理由三条，按分量排：

1. **一条 `delete` 打错库的代价不对等。** 这个服务的日常任务是删数据（90 天清理）。
   两个库在同一个实例里，`DATABASE_URL` 写错一个串，删的就是全站用户的账号表。分实例
   之后最坏情况是把自己的埋点删掉，那是可再生数据（客户端还有本地 jsonl）。
2. **升级和故障隔开。** account 那个库要重启、要调参、可能哪天换 major 版本；
   track 的 `shared_buffers=64MB` 和 `max_connections=30` 是自己的，不用挤别人的连接池。
3. **备份口径不同。** 账号库要备份；埋点库不需要（丢了不影响任何人），
   分开就不用为「备份策略不一样」在同一个 pg_dump 脚本里写条件。

代价：多一个 postgres 进程，实测常驻约 30MB RSS，2G 的机器扛得住（`free -m` 还有
1.3G 可用）。如果哪天机器降到 1G，把 track 并进 account 实例的 `track` database 也
只需改 `DATABASE_URL` 加搬一次表结构，代码不用动。

## 部署

```bash
# 本机（~/.ssh/config 里有 relay，key 已通）
cd server/track && ./deploy.sh
```

`deploy.sh` 干四件事：`/srv/track/.env` 不存在就现场生成（`POSTGRES_PASSWORD` 和
`TRACK_SECRET` 都是 `openssl rand -hex 32`，已存在则绝不覆盖）、rsync、
`docker compose up -d --build`、打 `/healthz`。

`TRACK_SECRET` 生成后要取回来两处落地：

```bash
ssh relay "grep TRACK_SECRET /srv/track/.env"     # 填进 App 的签名配置
# 同时追加到本机 .secrets/server.md 的「埋点上报」小节（那个文件不入库）
```

服务器上 `/srv/track` 的形状和 `/srv/admin` 一样：`docker-compose.yml` + `.env` +
`web/`，数据在 `pgdata/`。

### Caddy

`/srv/cfg/Caddyfile` 是生产入口，改之前先备份：

```bash
ssh relay 'cd /srv/cfg && cp Caddyfile Caddyfile.bak-$(date +%Y%m%d-%H%M%S)'
```

在 `/admin/*` 那段后面加：

```
	handle_path /t/* {
		reverse_proxy 127.0.0.1:8092
	}
```

然后**validate 通过再 reload**（不是 restart，restart 会断掉正在走的连接）：

```bash
ssh relay 'docker exec cfg caddy validate --config /srv/cfg/Caddyfile'
ssh relay 'docker exec cfg caddy reload   --config /srv/cfg/Caddyfile'
```

改完必须回头验一遍没被波及的两条：

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://149.104.21.177.sslip.io/admin/login   # 401
curl -s https://149.104.21.177.sslip.io/api/classes/_User                              # Parse 的 JSON
```

## 验收

```bash
# 单元测试（不需要数据库/网络，psycopg 在 .venv 里）
.venv/bin/python -m unittest discover -s tests -v

# 契约逐条打接口（要跑着的服务和 TRACK_SECRET）
TRACK_SECRET=<.env 里那串> python3 acceptance.py http://127.0.0.1:8092
TRACK_SECRET=<.env 里那串> python3 acceptance.py https://149.104.21.177.sslip.io/t
```

`tests/test_events.py` 盯的是「哪些问题拒整批、哪些只拒一条」—— 这条分界在
2026-10-25 之前搞反了，见上面「两种拒收」那一段。`tests/test_signature.py` 是
`ReportTest.java` 注释里点名要求存在的那份服务端向量，两边用同一个 secret 和 body。

`acceptance.py` 覆盖：healthz、2 条合法事件、错签名 401、gzip、300 条 413、
单条超 4KB → 200+rejected:1、数字错误码 → 收、好+坏混批 → 各计一条、
同一 event_id 发两次去重、坏 JSON 400、连打 70 次出 429。限流那条放最后跑，
跑完这个 IP 一分钟内是被限的。

## 查数

日常看数据用后台的 `/admin/data`（读这里的 `/stats/overview`、`/stats/events`，
口令 `STATS_TOKEN`）。下面这些 SQL 是页面还没覆盖的问题时手工查的。

两个接口的判定口径都在 `web/app/stats.py` 顶部：「取消/竞态」（请求被更新的取代、
宿主已关闭、排队超时、InterruptedException）不算失败，单独计数。

```sql
-- 最近一小时的播放失败，按阶段和错误码
select stage, code, count(*) from events
where kind='play' and name='play_error' and at > now() - interval '1 hour'
group by 1,2 order by 3 desc;

-- 哪个源最慢（首帧 p90）
select source_id, count(*) n,
       percentile_disc(0.9) within group (order by ms) p90
from events where name='play_first_frame' and at > now() - interval '7 days'
group by 1 order by n desc;

-- 版本分布
select app_version, count(distinct device_id) devices from events
where at > now() - interval '7 days' group by 1 order by 2 desc;
```

## 隐私边界

只采 `device_id`（安装时生成的 uuid）、机型、系统版本、App 版本。不采账号、不采集
手机号、不采内容标题 —— 需要标题时按 `content_id` 回源查。设置页的「不发送使用统计」
关掉之后只上报崩溃，这条在客户端实现，服务端不做区分（它也没有能区分的信息）。

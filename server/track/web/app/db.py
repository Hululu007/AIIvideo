"""存储层：一张明细表 events + 一张日聚合表 events_daily。

明细只留 RETENTION_DAYS 天，聚合永久。这样「上周某天的播放失败率」这一类
问题过后还能答，代价只有几十行/天。
"""
from __future__ import annotations

import time
from contextlib import contextmanager
from typing import Any, Iterator

from .config import DATABASE_URL, DB_CONNECT_RETRIES, RETENTION_DAYS

# 表结构照 TODO 里那张设计稿，只多两样东西：
#   · event_id 的唯一索引（离线队列会重发，靠它 on conflict do nothing 去重）
#   · events_daily 的 (day, kind, name, source_id) 主键（聚合可重跑，不产生重复行）
SCHEMA = """
create table if not exists events (
  id           bigserial primary key,
  event_id     text not null,
  device_id    text not null,
  session_id   text,
  at           timestamptz not null,
  received_at  timestamptz not null default now(),
  kind         text not null,
  name         text not null,
  source_id    text,
  stage        text,
  code         text,
  ms           integer,
  props        jsonb not null default '{}'::jsonb,
  app_version  text,
  app_vc       integer,
  os           integer,
  model        text,
  patch        integer
);
create unique index if not exists events_event_id_uidx on events (event_id);
create index if not exists events_at_idx on events (at desc);
create index if not exists events_kind_name_at_idx on events (kind, name, at desc);
create index if not exists events_source_at_idx on events (source_id, at desc);
create index if not exists events_device_at_idx on events (device_id, at desc);

create table if not exists events_daily (
  day         date        not null,
  kind        text        not null,
  name        text        not null,
  source_id   text        not null default '',
  events      integer     not null,
  devices     integer     not null,
  errors      integer     not null default 0,
  ms_p50      integer,
  ms_p95      integer,
  updated_at  timestamptz not null default now(),
  primary key (day, kind, name, source_id)
);
create index if not exists events_daily_kind_idx on events_daily (kind, name, day desc);
create index if not exists events_daily_source_idx on events_daily (source_id, day desc);
"""

INSERT_SQL = """
insert into events (
  event_id, device_id, session_id, at, kind, name, source_id, stage, code, ms,
  props, app_version, app_vc, os, model, patch
) values (
  %(event_id)s, %(device_id)s, %(session_id)s, %(at)s, %(kind)s, %(name)s,
  %(source_id)s, %(stage)s, %(code)s, %(ms)s,
  %(props)s, %(app_version)s, %(app_vc)s, %(os)s, %(model)s, %(patch)s
)
on conflict (event_id) do nothing
"""

# 重跑幂等：先按当天重算，再覆盖。同一个日期跑十遍结果一样。
AGGREGATE_SQL = """
insert into events_daily (day, kind, name, source_id, events, devices, errors, ms_p50, ms_p95, updated_at)
select
  %(day)s::date                                        as day,
  kind,
  name,
  coalesce(source_id, '')                              as source_id,
  count(*)::int                                        as events,
  count(distinct device_id)::int                       as devices,
  count(*) filter (where code is not null)::int        as errors,
  percentile_disc(0.5) within group (order by ms) filter (where ms is not null) as ms_p50,
  percentile_disc(0.95) within group (order by ms) filter (where ms is not null) as ms_p95,
  now()                                                as updated_at
from events
where (at at time zone %(tz)s)::date = %(day)s::date
group by kind, name, coalesce(source_id, '')
on conflict (day, kind, name, source_id) do update set
  events     = excluded.events,
  devices    = excluded.devices,
  errors     = excluded.errors,
  ms_p50     = excluded.ms_p50,
  ms_p95     = excluded.ms_p95,
  updated_at = now()
"""


@contextmanager
def connect() -> Iterator[Any]:
    import psycopg
    from psycopg.rows import dict_row

    if not DATABASE_URL:
        raise RuntimeError("没有配置 DATABASE_URL")
    with psycopg.connect(DATABASE_URL, row_factory=dict_row, connect_timeout=5) as conn:
        yield conn


def init(wait: bool = True) -> None:
    """建表。postgres 刚起时 pg_isready 可能已经通过但还拒连接，所以带重试。"""
    attempts = DB_CONNECT_RETRIES if wait else 1
    last: Exception | None = None
    for attempt in range(attempts):
        try:
            with connect() as conn:
                conn.execute(SCHEMA)
            return
        except Exception as exc:  # noqa: BLE001 —— 启动期只关心「还没好」
            last = exc
            if attempt + 1 < attempts:
                time.sleep(1.5)
    raise RuntimeError(f"数据库初始化失败：{last}")


def ping() -> bool:
    try:
        with connect() as conn:
            conn.execute("select 1")
        return True
    except Exception:
        return False


def insert_events(rows: list[dict]) -> int:
    """返回真正写进去的行数。重发的 event_id 被 on conflict 吃掉，不计入。"""
    if not rows:
        return 0
    with connect() as conn, conn.cursor() as cur:
        cur.executemany(INSERT_SQL, rows)
        return max(cur.rowcount, 0)


def aggregate_day(day: str, tz: str) -> int:
    with connect() as conn, conn.cursor() as cur:
        cur.execute(AGGREGATE_SQL, {"day": day, "tz": tz})
        return max(cur.rowcount, 0)


def purge_old(days: int = RETENTION_DAYS) -> int:
    with connect() as conn, conn.cursor() as cur:
        cur.execute(
            "delete from events where at < now() - make_interval(days => %s)",
            (days,),
        )
        return max(cur.rowcount, 0)


def stats() -> dict:
    """一个便宜的概览，给 healthz 和排障用。"""
    with connect() as conn:
        row = conn.execute(
            "select count(*) as n, min(at) as oldest, max(received_at) as latest from events"
        ).fetchone()
        daily = conn.execute("select count(*) as n from events_daily").fetchone()
    return {"events": row["n"], "oldest": row["oldest"], "latest": row["latest"],
            "daily_rows": daily["n"]}

"""给后台看的只读查询。

为什么要经过这里、而不是让后台直接连库：

  · track-db 一步都没出 compose 网络（见 docker-compose.yml 顶部那段），后台是
    network_mode: host，够不着它；
  · 就算够得着，也不该把埋点库的写权限分给第二个服务 —— 这张表有个每天 00:10
    重算、90 天清理的定时任务，多一个写入方就多一种「谁删的」的可能。

所以读也走 HTTP，用 X-Stats-Token 这一把头（和 App 的 X-Sig 不是一回事：那个是
验请求体完整性，这个是验「你是后台」）。

口径：
  · 「今天/最近 N 天」一律按 AGG_TZ 切（用户都在国内，按 UTC 切会把凌晨 0-8 点算到
    前一天）；
  · devices 是设备数，不是人数 —— 没有账号体系那一步，别在页面上写「用户数」；
  · errors 的定义是「code 不为空」，也就是这条事件描述了一次失败。
"""
from __future__ import annotations

from typing import Any

from .config import AGG_TZ
from .db import connect

# 「失败」里混着一大批不是故障的：用户快速划过时上一个请求被取消、spider 宿主
# 生命周期结束时把在途调用掐掉、同一宿主被占满时的排队超时。这些 App 那边迟早
# 该静默掉（bridgeFailure 的安静名单），但在那之前，页面上的「失败率」必须先
# 把它们剔出去，否则 93% 这个数字只会让人以为全线崩了。
NOISE_SQL = """(
  code is not null and (
    code = 'InterruptedException'
    or coalesce(props ->> 'msg', '') ~ '被更新的请求取代|宿主已关闭|排队超时'
  )
)"""


def overview(days: int = 7) -> dict:
    days = max(1, min(int(days), 90))
    tz = AGG_TZ
    params = {"days": days, "tz": tz}
    with connect() as conn:
        daily = conn.execute(
            """
            select to_char((at at time zone %(tz)s)::date, 'MM-DD')      as day,
                   ((at at time zone %(tz)s)::date)::text                as iso,
                   count(*)::int                                          as events,
                   count(distinct device_id)::int                         as devices,
                   count(distinct session_id)::int                         as sessions,
                   count(*) filter (where kind = 'crash')::int            as crashes,
                   count(*) filter (where code is not null)::int          as errors,
                   count(*) filter (where """ + NOISE_SQL + """)::int as noise
            from events
            where at >= now() - make_interval(days => %(days)s)
            group by 1, 2
            order by 2
            """,
            params,
        ).fetchall()

        total = conn.execute(
            """
            select count(*)::int                                   as events,
                   count(distinct device_id)::int                  as devices,
                   count(distinct session_id)::int                   as sessions,
                   count(*) filter (where kind = 'crash')::int      as crashes,
                   count(*) filter (where code is not null)::int    as errors,
                   count(*) filter (where """ + NOISE_SQL + """)::int as noise,
                   to_char(min(at) at time zone %(tz)s, 'MM-DD HH24:MI') as since,
                   to_char(max(at) at time zone %(tz)s, 'MM-DD HH24:MI') as latest
            from events
            where at >= now() - make_interval(days => %(days)s)
            """,
            params,
        ).fetchone()

        kinds = conn.execute(
            """
            select kind, name,
                   count(*)::int                                 as events,
                   count(distinct device_id)::int                as devices,
                   count(*) filter (where code is not null)::int  as errors
            from events
            where at >= now() - make_interval(days => %(days)s)
            group by kind, name
            order by events desc
            """,
            params,
        ).fetchall()

        sources = conn.execute(
            """
            select coalesce(nullif(source_id, ''), '（没报源）')          as source_id,
                   count(*)::int                                       as events,
                   count(distinct device_id)::int                       as devices,
                   count(*) filter (where code is not null)::int        as errors,
                   count(*) filter (where kind = 'play')::int           as plays,
                   to_char(max(at) at time zone %(tz)s, 'MM-DD HH24:MI')  as last_at
            from events
            where at >= now() - make_interval(days => %(days)s)
            group by 1
            order by events desc
            limit 20
            """,
            params,
        ).fetchall()

        errors = conn.execute(
            """
            select kind, coalesce(stage, '')                             as stage,
                   coalesce(code, '')                                    as code,
                   coalesce(props ->> 'msg', '')                          as msg,
                   count(*)::int                                          as n,
                   count(distinct device_id)::int                          as devices,
                   bool_or(""" + NOISE_SQL + """)                      as noise,
                   to_char(max(at) at time zone %(tz)s, 'MM-DD HH24:MI')   as last_at
            from events
            where at >= now() - make_interval(days => %(days)s)
              and code is not null
            group by 1, 2, 3, 4
            order by bool_or(""" + NOISE_SQL + """), n desc
            limit 20
            """,
            params,
        ).fetchall()

        versions = conn.execute(
            """
            select coalesce(app_version, '?')                             as app_version,
                   count(distinct device_id)::int                          as devices,
                   count(*)::int                                           as events
            from events
            where at >= now() - make_interval(days => %(days)s)
            group by 1
            order by devices desc
            """,
            params,
        ).fetchall()

        models = conn.execute(
            """
            select coalesce(model, '?')                                   as model,
                   count(distinct device_id)::int                          as devices
            from events
            where at >= now() - make_interval(days => %(days)s)
            group by 1
            order by devices desc
            limit 10
            """,
            params,
        ).fetchall()

    return {
        "days": days,
        "tz": tz,
        "daily": daily,
        "total": total,
        "kinds": kinds,
        "sources": sources,
        "errors": errors,
        "versions": versions,
        "models": models,
    }


def recent(limit: int = 80, kind: str = "", clean: bool = False) -> list[dict[str, Any]]:
    """clean=True 时剔掉取消/竞态那批 —— 事件流里它们能占到八九成，
    不剔的话真正想看的那几条会被淹掉。"""
    limit = max(1, min(int(limit), 300))
    kind = (kind or "").strip()
    with connect() as conn:
        rows = conn.execute(
            """
            select to_char(at at time zone %(tz)s, 'MM-DD HH24:MI:SS') as at,
                   kind, name,
                   coalesce(source_id, '')            as source_id,
                   coalesce(stage, '')                as stage,
                   coalesce(code, '')                 as code,
                   ms,
                   left(coalesce(props ->> 'msg', ''), 160) as msg,
                   left(coalesce(app_version, ''), 16)      as app_version,
                   coalesce(os, 0)                    as os,
                   left(coalesce(model, ''), 24)      as model,
                   left(device_id, 8)                 as device,
                   left(coalesce(session_id, ''), 8)  as session
            from events
            where (%(kind)s = '' or kind = %(kind)s)
              and (not %(clean)s or not """ + NOISE_SQL + """)
            order by at desc
            limit %(limit)s
            """,
            {"kind": kind, "limit": limit, "tz": AGG_TZ, "clean": bool(clean)},
        ).fetchall()
    return rows

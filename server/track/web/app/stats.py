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
  · **fails 是失败率的分子**：errors 再剔掉取消/竞态、再剔掉 `kind='js'`。
    两个不是一回事，页面上用 fails，不要用 errors（见下面 FAIL_SQL）。
"""
from __future__ import annotations

from typing import Any

from .config import AGG_TZ
from .db import connect

# 「失败」里混着一大批不是故障的：用户快速划过时上一个请求被取消、spider 宿主
# 生命周期结束时把在途调用掐掉、同一宿主被占满时的排队超时。这些 App 那边迟早
# 该静默掉（bridgeFailure 的安静名单），但在那之前，页面上的「失败率」必须先
# 把它们剔出去，否则 93% 这个数字只会让人以为全线崩了。
#
# ── 为什么 InterruptedIOException 要按消息判，不能按类名 ──────────────
# `SocketTimeoutException extends InterruptedIOException`，okhttp 的 callTimeout
# 也抛 `InterruptedIOException("timeout")`。那两条都是**真超时**，线上 26 条
# SocketTimeoutException 均值 30 秒、全在 detail 阶段，把它们算成取消就等于
# 把「站点慢到读不出来」这个信号藏起来。只有 message 是 interrupted / Canceled /
# Socket closed 的才真是「我们主动掐的」。
#
# 这里只影响 /stats/* 的读数，不改 events_daily 的 errors 判据（那边还是
# 「code 非空」），所以改这段不用重跑聚合。
NOISE_SQL = """(
  code is not null and (
    code in ('InterruptedException', 'DeadObjectException', 'ConnectionShutdownException')
    or (code = 'InterruptedIOException'
        and coalesce(props ->> 'msg', '') ~* '^(interrupted|canceled|cancelled|socket closed)$')
    or coalesce(props ->> 'msg', '') ~ '被更新的请求取代|宿主已关闭|排队超时'
  )
)"""

# 失败率的分子。在 errors 的基础上再剔掉 `kind='js'`（2.0.23 起 App 会报 JS 异常）。
#
# 为什么要单剔 JS：**它没有对应的分母。** 一次坏掉的渲染循环会在几秒内刷出成百上千条
# js_error，而背后只是「用户点了一下，这一屏没渲染出来」这一次操作。混进失败率，
# 这个数就会因为和源健康完全无关的原因上下跳 —— 和 10-24 那次「失败率 93% 是假的」
# 是同一种误读，只是换了个来源。
#
# 注意这不是「不算失败」，是「不算这一个比率」。js 事件照样进「失败排行」和事件流，
# 那才是真正要看它的地方；页面上单列成一个「JS 异常」的计数。
FAIL_SQL = "(code is not null and kind <> 'js' and not " + NOISE_SQL + ")"


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
                   count(*) filter (where """ + NOISE_SQL + """)::int as noise,
                   count(*) filter (where """ + FAIL_SQL + """)::int as fails,
                   count(*) filter (where kind = 'js')::int               as js
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
                   count(*) filter (where """ + FAIL_SQL + """)::int as fails,
                   count(*) filter (where kind = 'js')::int         as js,
                   -- 播放链路的三段。这一组以前恒为 0：VlcPlayerActivity 里
                   -- 一条 Report 都没有，全库 kind='play' 是 0 条 —— 「播不了」
                   -- 这个最常见的抱怨，后台连分子分母都拿不到。
                   count(*) filter (where name = 'play_start')::int       as play_starts,
                   count(*) filter (where name = 'play_first_frame')::int as play_frames,
                   count(*) filter (where name = 'play_error')::int       as play_errors,
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
                   count(*) filter (where """ + FAIL_SQL + """)::int as fails,
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

    # 出帧率 = 出过第一帧的会话 / 尝试过的会话。
    #
    # 分子分母都按事件数算，不按会话：play_start 和 play_first_frame 各自带 mode，
    # 想按会话归并还得先有 session_id 对齐，而 session_id 在 :spider 和主进程之间
    # 不是同一个 —— 这个口径已经够回答问题（「有多大比例的播放没能出画面」）。
    # 分母为 0 时给 None，让前端显示「还没有数据」而不是 0%。
    play_starts = int(total["play_starts"] or 0)
    play_frames = int(total["play_frames"] or 0)
    play_rate = round(play_frames * 100.0 / play_starts, 1) if play_starts else None

    return {
        "days": days,
        "tz": tz,
        "play_ok_rate": play_rate,
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

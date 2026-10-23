"""两个每日任务：聚合前一天、删 90 天前的明细。

两件事都要能**手动跑**（线上补聚合、查问题时重算），所以逻辑在 run_daily() 里，
定时器只是它的一个调用者：

    docker compose exec web python -m app.jobs             # 跑昨天
    docker compose exec web python -m app.jobs 2026-10-20  # 指定日期重算
    docker compose exec web python -m app.jobs --purge     # 只删过期明细
"""
from __future__ import annotations

import asyncio
import logging
import sys
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from . import db
from .config import AGG_AT, AGG_TZ, RETENTION_DAYS

log = logging.getLogger("track.jobs")


def _today() -> date:
    return datetime.now(ZoneInfo(AGG_TZ)).date()


def run_daily(day: date | None = None, purge: bool = True) -> dict:
    target = day or (_today() - timedelta(days=1))
    aggregated = db.aggregate_day(target.isoformat(), AGG_TZ)
    result = {"day": target.isoformat(), "daily_rows": aggregated, "deleted": 0}
    if purge:
        result["deleted"] = db.purge_old(RETENTION_DAYS)
    log.info("日聚合 %s：%s 个 (kind,name,source) 组合；清理 %s 行",
             result["day"], result["daily_rows"], result["deleted"])
    return result


def _next_run_at(now: datetime) -> datetime:
    hour, minute = (int(part) for part in AGG_AT.split(":"))
    target = now.replace(hour=hour, minute=minute, second=0, microsecond=0)
    if target <= now:
        target += timedelta(days=1)
    return target


async def scheduler() -> None:
    """常驻协程。启动先补跑一次昨天（容器重启不会漏掉当天），再等下一个 AGG_AT。"""
    loop = asyncio.get_running_loop()
    try:
        await loop.run_in_executor(None, run_daily)
    except Exception:  # noqa: BLE001 —— 补跑失败不能把服务带死
        log.exception("启动补跑日聚合失败")

    while True:
        now = datetime.now(ZoneInfo(AGG_TZ))
        delay = (_next_run_at(now) - now).total_seconds()
        await asyncio.sleep(max(delay, 60.0))
        try:
            await loop.run_in_executor(None, run_daily)
        except Exception:  # noqa: BLE001
            log.exception("定时日聚合失败")


def main(argv: list[str]) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if "--purge" in argv:
        print({"deleted": db.purge_old(RETENTION_DAYS)})
        return 0
    day = None
    for arg in argv[1:]:
        if arg.startswith("-"):
            continue
        day = date.fromisoformat(arg)
    print(run_daily(day))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

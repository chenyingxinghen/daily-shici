"""每日选诗定时任务（`docs/03 §5.1`）。

由 `main.py` 的 APScheduler 在**同一进程内**每日 00:05 触发。
不引 Celery：一个每日任务不值一套 broker。
"""

from __future__ import annotations

import logging

from ..services import daily as daily_service

logger = logging.getLogger("shici.jobs")


def refill_daily_window() -> None:
    """补齐滚动窗口 `[今天-365, 今天+90]` 并清理窗口外记录。

    幂等：`pick_and_store` 内部用 `INSERT OR IGNORE`，
    多实例重复触发不会写出两条。
    """
    try:
        result = daily_service.refill_window()
        logger.info(
            "每日选诗窗口已补齐：%s，候选池 %s，新生成 %s 天",
            result["date"],
            result["pool_size"],
            result["created"],
        )
    except Exception:  # noqa: BLE001 - 定时任务不能因单次失败而终止调度
        logger.exception("每日选诗任务失败")


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    refill_daily_window()

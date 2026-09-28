"""`/daily`、`/daily/batch`（`docs/02 §3.1–3.2`）。

`/daily` **只读 `daily_pick` 表，从不临时计算** —— 选诗由定时任务预生成，
接口只负责取。这样「人工预置某天的诗」与「事后追溯推了什么」才成立。

⚠️ `/daily` 返回的是**完整 `PoemDetail`**（含正文）。这是客户端能离线读每日一诗
的根本原因：它把未来 30 天的正文一起缓存，与用户装没装数据包无关。
"""

from __future__ import annotations

from datetime import date as date_type
from datetime import datetime, time, timedelta

from fastapi import APIRouter, Query, Response

from ..config import settings
from ..errors import ApiError
from ..schemas import DailyBatchResponse, DailyItem, DailyResponse
from ..services import daily as daily_service
from ..services import poems as poems_service

router = APIRouter(tags=["daily"])


def _parse_date(raw: str | None, default: date_type) -> date_type:
    if not raw:
        return default
    try:
        return datetime.strptime(raw, "%Y-%m-%d").date()
    except ValueError:
        raise ApiError("INVALID_PARAM", f"日期格式应为 YYYY-MM-DD：{raw}") from None


def _seconds_until_midnight() -> int:
    now = datetime.now()
    tomorrow = datetime.combine(now.date() + timedelta(days=1), time.min)
    return max(60, int((tomorrow - now).total_seconds()))


def _ensure_pick(target: date_type) -> int | None:
    """取当天留档；缺失时补一次（`INSERT OR IGNORE` 幂等）。"""
    poem_id = daily_service.get_pick(target)
    if poem_id is None:
        daily_service.pick_and_store(target)
        poem_id = daily_service.get_pick(target)
    return poem_id


@router.get("/daily", response_model=DailyResponse)
def get_daily(
    response: Response,
    # 查询参数名必须是 `date`，但该名字与 `datetime.date` 冲突，
    # 故 Python 侧用 `day` + `alias="date"`，而不是耍「内建名遮蔽」的花招。
    day: str | None = Query(default=None, alias="date", description="YYYY-MM-DD，默认今天"),
):
    today = date_type.today()
    target = _parse_date(day, today)

    if not daily_service.in_window(target, today):
        raise ApiError(
            "INVALID_PARAM",
            f"日期超出可查窗口 [今天-{settings.daily_past_days}, 今天+{settings.daily_future_days}]",
        )

    poem_id = _ensure_pick(target)
    poem = poems_service.fetch_detail(poem_id) if poem_id else None
    if poem is None:
        raise ApiError("POEM_NOT_FOUND", f"{target.isoformat()} 没有可用的每日一诗")

    # 客户端当天只需请求一次（docs/02 §3.1）
    response.headers["Cache-Control"] = f"public, max-age={_seconds_until_midnight()}"
    return DailyResponse(
        date=target.isoformat(),
        poem=poem,
        pool_size=daily_service.pick_pool_size(target),
    )


@router.get("/daily/batch", response_model=DailyBatchResponse)
def get_daily_batch(
    start: str | None = Query(default=None, alias="from", description="YYYY-MM-DD，默认今天"),
    days: int = Query(default=30, ge=1, le=30),
):
    """批量预取。**超出窗口的日期静默跳过，不报错** ——
    预取是尽力而为，不该因边界日期让整批失败（docs/02 §3.2）。
    """
    today = date_type.today()
    first = _parse_date(start, today)

    items: list[DailyItem] = []
    for offset in range(days):
        target = first + timedelta(days=offset)
        if not daily_service.in_window(target, today):
            continue
        poem_id = _ensure_pick(target)
        if poem_id is None:
            continue
        poem = poems_service.fetch_detail(poem_id)
        if poem is None:
            continue
        items.append(DailyItem(date=target.isoformat(), poem=poem))

    if not items:
        raise ApiError("INVALID_PARAM", "给定范围内没有可预取的日期")
    return DailyBatchResponse(items=items)

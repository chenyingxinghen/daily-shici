"""每日选诗（`docs/03 §5.1` / `docs/02 §4`）。

**服务端选诗是主路径，客户端本地选诗只是兜底，两者不做一致性要求** ——
服务端要的是可操作性（人工干预、主题策划、权重调节），客户端要的只是断网时别开天窗。

三个必须做对的地方：

1. **加权随机不能用 `ORDER BY RANDOM() * weight`** —— 那是「随机排序后乘权重」，
   不是加权抽样。正确做法是前缀和 + 二分。
2. **幂等靠 `daily_pick.date` 主键冲突**（`INSERT OR IGNORE`），
   多实例重复触发不会写出两条。
3. **人工预置优先**：`is_manual = 1` 的记录不覆盖，这是「中秋挑一首咏月诗」的落点。
"""

from __future__ import annotations

import bisect
import random
from datetime import date, datetime, timedelta, timezone

from ..config import settings
from .. import db


def _pool(min_weight: int) -> list[tuple[int, int]]:
    """候选池 = 入库全量（`weight` 只控制相对概率，不控制池子边界）。

    返回 `[(poem_id, effective_weight), …]`，`effective_weight` 已按三层换算成
    相对概率（W3=100 / W2=30 / W1=1）。
    """
    if settings.include_copyrighted:
        where, params = "p.weight >= ?", [min_weight]
    else:
        where, params = "p.weight >= ? AND p.is_public_domain = 1", [min_weight]

    with db.read_cursor() as cursor:
        rows = cursor.execute(
            f"SELECT poem_id, weight FROM poem p WHERE {where} ORDER BY poem_id",
            params,
        ).fetchall()

    return [(row["poem_id"], _effective_weight(row["weight"])) for row in rows]


def _effective_weight(raw_weight: int) -> int:
    """把 `poem.weight`（100 / 30 / 1）映射成抽样权重。

    三层是选本 / 全收型经典 / 全集的分层（`docs/02 §4.1`），
    所以这里直接取原始值当概率权重即可 —— W3 的诗被抽中的概率是 W1 的 100 倍。
    `settings.weight_w3` / `weight_w2` 保留为可调项，未改分层时等于原始值。
    """
    if raw_weight >= 100:
        return settings.weight_w3
    if raw_weight >= 30:
        return settings.weight_w2
    return raw_weight


def _weighted_pick(pool: list[tuple[int, int]], excluded: set[int]) -> int | None:
    """前缀和 + 二分。**不用 `ORDER BY RANDOM()`** —— 85 万行全表排序约 800 ms。"""
    candidates = [(pid, w) for pid, w in pool if pid not in excluded and w > 0]
    if not candidates:
        return None

    prefix: list[int] = []
    total = 0
    for _, weight in candidates:
        total += weight
        prefix.append(total)

    point = random.randrange(total)
    index = bisect.bisect_right(prefix, point)
    return candidates[min(index, len(candidates) - 1)][0]


def _recently_used(today: date) -> set[int]:
    """近 [daily_no_repeat_days] 天已用过的 poem_id 集合。

    窗口外的不算 —— 它们已被清理，重复出现是可接受的。
    """
    since = (today - timedelta(days=settings.daily_no_repeat_days)).isoformat()
    with db.read_cursor() as cursor:
        rows = cursor.execute(
            "SELECT poem_id FROM daily_pick WHERE date >= ?", (since,)
        ).fetchall()
    return {row["poem_id"] for row in rows}


def pick_and_store(target: date, pool: list[tuple[int, int]] | None = None) -> int | None:
    """为 [target] 选一首并落库。已有 `is_manual = 1` 的记录**不覆盖**。"""
    pool = pool if pool is not None else _pool(settings.daily_pool_min_weight)

    with db.read_cursor() as cursor:
        existing = cursor.execute(
            "SELECT poem_id, is_manual FROM daily_pick WHERE date = ?", (target.isoformat(),)
        ).fetchone()
    if existing is not None and existing["is_manual"]:
        return None  # 人工预置优先

    poem_id = _weighted_pick(pool, _recently_used(target))
    if poem_id is None:
        return None

    with db.write_cursor() as cursor:
        # 幂等：靠 date 主键冲突，多实例重复触发不会写出两条（docs/03 §5.1）
        cursor.execute(
            "INSERT OR IGNORE INTO daily_pick(date, poem_id, pool_size, is_manual, created_at)"
            " VALUES (?,?,?,0,?)",
            (
                target.isoformat(),
                poem_id,
                len(pool),
                datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            ),
        )
    return poem_id


def refill_window(reference: date | None = None) -> dict:
    """补齐滚动窗口 `[今天-? , 今天+90]` 并清理窗口外记录（docs/03 §5.1）。

    每日 00:05 触发（避开 00:00 的整点资源竞争）。
    """
    today = reference or date.today()
    pool = _pool(settings.daily_pool_min_weight)
    created = 0

    for offset in range(0, settings.daily_future_days + 1):
        if pick_and_store(today + timedelta(days=offset), pool) is not None:
            created += 1

    with db.write_cursor() as cursor:
        cursor.execute(
            "DELETE FROM daily_pick WHERE date < ?",
            ((today - timedelta(days=settings.daily_past_days)).isoformat(),),
        )

    return {"date": today.isoformat(), "pool_size": len(pool), "created": created}


def get_pick(target: date) -> int | None:
    with db.read_cursor() as cursor:
        row = cursor.execute(
            "SELECT poem_id FROM daily_pick WHERE date = ?", (target.isoformat(),)
        ).fetchone()
    return row["poem_id"] if row else None


def pick_pool_size(target: date) -> int:
    """当天选诗时的候选池大小（`docs/02 §3.1` 的 `pool_size`）。

    暴露它是为了诊断「为什么某天的诗很冷门」—— 池子小说明当天候选被
    「365 天不重复」窗口收窄了，而不是选诗算法出问题。
    """
    with db.read_cursor() as cursor:
        row = cursor.execute(
            "SELECT pool_size FROM daily_pick WHERE date = ?", (target.isoformat(),)
        ).fetchone()
    return row["pool_size"] if row else 0


def in_window(target: date, today: date | None = None) -> bool:
    """窗口外返回 False，由调用方转成 `INVALID_PARAM`（docs/02 §3.1）。"""
    today = today or date.today()
    return (
        today - timedelta(days=settings.daily_past_days)
        <= target
        <= today + timedelta(days=settings.daily_future_days)
    )

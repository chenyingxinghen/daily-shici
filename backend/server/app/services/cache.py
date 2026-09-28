"""筛选器计数的计算与缓存（`docs/03 §5.2`）。

`/facets` 的计数是全表 `GROUP BY`，83.5 万行约 200 ms。策略：
首次请求计算并写入 `facets_cache`，之后直接读缓存；
**ETL 结束或版权开关变更时主动失效**（删掉该行），而不是等 TTL 过期。

词牌约 1000 条是其中最慢的一段，单独统计。
"""

from __future__ import annotations

import json
from datetime import datetime, timezone

from .. import db
from ..config import settings
from . import slugs


def compute() -> dict:
    """全量统计。**已应用版权开关** —— 开关为 OFF 时计数也一并收窄（docs/02 §6.4）。"""
    # 版权开关是唯一允许拼接的片段（值域两个字面量，无用户输入）
    where = "" if settings.include_copyrighted else "WHERE p.is_public_domain = 1"
    and_pd = "" if settings.include_copyrighted else "AND p.is_public_domain = 1"

    with db.read_cursor() as cursor:
        periods = cursor.execute(
            f"""
            SELECT d.period AS name, d.period_order AS ord, COUNT(*) AS count
            FROM poem p JOIN dynasty d ON d.dynasty_id = p.dynasty_id
            {where}
            GROUP BY d.period ORDER BY d.period_order
            """
        ).fetchall()

        dynasties = cursor.execute(
            f"""
            SELECT d.name AS name, d.period AS period, d.dynasty_order AS ord, COUNT(*) AS count
            FROM poem p JOIN dynasty d ON d.dynasty_id = p.dynasty_id
            {where}
            GROUP BY d.name ORDER BY d.dynasty_order
            """
        ).fetchall()

        genres = cursor.execute(
            f"SELECT p.genre AS name, COUNT(*) AS count FROM poem p {where} GROUP BY p.genre"
        ).fetchall()

        cipais = cursor.execute(
            f"""
            SELECT p.cipai AS name, COUNT(*) AS count FROM poem p
            WHERE p.cipai IS NOT NULL {and_pd}
            GROUP BY p.cipai ORDER BY count DESC
            """
        ).fetchall()

        collections = cursor.execute(
            f"""
            SELECT c.slug AS slug, c.name AS name, c.builtin AS builtin, COUNT(*) AS count
            FROM collection c
            JOIN collection_member m ON m.collection_id = c.collection_id
            JOIN poem p ON p.poem_id = m.poem_id
            {where}
            GROUP BY c.slug ORDER BY c.sort_order
            """
        ).fetchall()

    return {
        "periods": [
            {"key": slugs.period_key(row["name"]), "name": row["name"], "count": row["count"]}
            for row in periods
        ],
        "dynasties": [
            {
                "key": slugs.dynasty_key(row["name"]),
                "name": row["name"],
                "period": slugs.period_key(row["period"]),
                "count": row["count"],
            }
            for row in dynasties
        ],
        "genres": [
            {"key": slugs.genre_key(row["name"]), "name": row["name"], "count": row["count"]}
            for row in genres
        ],
        # 词牌本身就是中文名，key 与 name 相同（它不出现在筛选器 URL 之外）
        "cipais": [
            {"key": row["name"], "name": row["name"], "count": row["count"]}
            for row in cipais
        ],
        "collections": [
            {
                "slug": row["slug"],
                "name": row["name"],
                "count": row["count"],
                "builtin": bool(row["builtin"]),
            }
            for row in collections
        ],
    }


def get_or_compute(force: bool = False) -> tuple[dict, str]:
    """返回 `(payload, generated_at)`。缓存优先。"""
    if not force:
        with db.read_cursor() as cursor:
            row = cursor.execute(
                "SELECT payload, generated_at FROM facets_cache WHERE id = 1"
            ).fetchone()
        if row is not None:
            try:
                return json.loads(row["payload"]), row["generated_at"]
            except json.JSONDecodeError:
                pass  # 缓存损坏就当没有，重算一次

    payload = compute()
    generated_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    with db.write_cursor() as cursor:
        cursor.execute(
            "INSERT OR REPLACE INTO facets_cache(id, payload, generated_at) VALUES (1, ?, ?)",
            (json.dumps(payload, ensure_ascii=False), generated_at),
        )
    return payload, generated_at


def invalidate() -> None:
    """ETL 结束或版权开关变更时调用（docs/03 §5.2）。"""
    with db.write_cursor() as cursor:
        cursor.execute("DELETE FROM facets_cache WHERE id = 1")

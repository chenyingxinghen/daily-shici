"""诗词读取：列表分页、详情渲染、行→响应模型转换。

分页走 `services/query.py` 的 keyset；这里负责 SQL 组装与 DTO 渲染。
"""

from __future__ import annotations

import math

from .. import db
from ..config import settings
from ..schemas import AnnotationSource, AuthorRef, CollectionRef, PoemDetail, PoemSummary
from . import annotate, query

#: 列表查询的公共 SELECT。注意 `excerpt` 是冗余列 —— 只为它就不必读长诗的 content
#: overflow page（docs/03 §2.4，这是真实的 I/O 节省，不是覆盖索引）。
_SELECT = """
SELECT p.poem_id, p.title, p.excerpt, p.weight,
       a.name AS author, d.name AS dynasty, d.dynasty_order AS dynasty_order,
       p.genre
FROM poem p
JOIN author  a ON a.author_id  = p.author_id
JOIN dynasty d ON d.dynasty_id = p.dynasty_id
"""


def _to_summary(row) -> PoemSummary:
    return PoemSummary(
        poem_id=row["poem_id"],
        title=row["title"],
        author=row["author"],
        dynasty=row["dynasty"],
        genre=row["genre"],
        excerpt=row["excerpt"],
    )


def list_poems(
    filters: query.Filters,
    sort: str,
    cursor: str | None,
    limit: int,
    applied: dict[str, str],
) -> tuple[list[PoemSummary], str | None, int]:
    """返回 `(items, next_cursor, total)`。

    `total` 是**筛选后的总数**，用于客户端判断「还有多少」以及 §4.2 的
    「应有条数 > 本地条数 ⇒ 未下载」判定。
    """
    spec = query.SORTS.get(sort) or query.SORTS[query.DEFAULT_SORT]
    limit = max(1, min(limit, settings.max_limit))

    clauses, params = filters.where()
    joins = ""
    if filters.collection_id is not None:
        joins = "JOIN collection_member cm ON cm.poem_id = p.poem_id"
        clauses.append("cm.collection_id = ?")
        params.append(filters.collection_id)
    # period 直接走已 join 的 dynasty 表，无需额外 join；
    # 注意这里**不能**把 joins 重置为空 —— 那会连带清掉上面的 collection join。

    decoded = query.decode_cursor(cursor, spec.name)

    where_sql = ("WHERE " + " AND ".join(clauses)) if clauses else ""
    fetch_clauses = list(clauses)
    fetch_params = list(params)
    if decoded is not None:
        keyset_sql, keyset_params = spec.keyset(decoded)
        fetch_clauses.append(keyset_sql)
        fetch_params.extend(keyset_params)

    fetch_where = ("WHERE " + " AND ".join(fetch_clauses)) if fetch_clauses else ""

    with db.read_cursor() as cur:
        # 多取一行用于判断是否还有下一页 —— 比再跑一次 COUNT 便宜
        rows = cur.execute(
            f"{_SELECT} {joins} {fetch_where} ORDER BY {spec.order_by} LIMIT ?",
            [*fetch_params, limit + 1],
        ).fetchall()

        total = cur.execute(
            f"SELECT COUNT(*) FROM poem p JOIN dynasty d ON d.dynasty_id = p.dynasty_id"
            f" {joins} {where_sql}",
            params,
        ).fetchone()[0]

    has_more = len(rows) > limit
    page = rows[:limit]
    next_cursor = (
        query.encode_cursor(spec.name, spec.next_values(page[-1])) if has_more and page else None
    )
    return [_to_summary(row) for row in page], next_cursor, total


def count_only(filters: query.Filters) -> int:
    clauses, params = filters.where()
    joins = ""
    if filters.collection_id is not None:
        joins = "JOIN collection_member cm ON cm.poem_id = p.poem_id"
        clauses.append("cm.collection_id = ?")
        params.append(filters.collection_id)
    where_sql = ("WHERE " + " AND ".join(clauses)) if clauses else ""

    with db.read_cursor() as cur:
        return cur.execute(
            f"SELECT COUNT(*) FROM poem p JOIN dynasty d ON d.dynasty_id = p.dynasty_id"
            f" {joins} {where_sql}",
            params,
        ).fetchone()[0]


def fetch_detail(poem_id: int) -> PoemDetail | None:
    """详情。`collections` 最多返回 5 个，按合集 sort_order（docs/02 §2.2）。

    注释 / 译文 / 赏析从**另一个库**（`annotations.db`）合并进来，见 `docs/06 §3`。
    未收录时三个字段保持 `None` —— 这不只是「没数据」，是契约要求的正常状态，
    客户端据此隐藏区块（`docs/02 §6.3`）。
    """
    with db.read_cursor() as cur:
        row = cur.execute(
            """
            SELECT p.*, a.author_id AS author_id, a.name AS author_name,
                   d.name AS dynasty_name, d.period AS period_name
            FROM poem p
            JOIN author  a ON a.author_id  = p.author_id
            JOIN dynasty d ON d.dynasty_id = p.dynasty_id
            WHERE p.poem_id = ?
            """,
            (poem_id,),
        ).fetchone()

        if row is None:
            return None

        if not settings.include_copyrighted and not row["is_public_domain"]:
            return None

        collections = cur.execute(
            """
            SELECT c.slug, c.name
            FROM collection c JOIN collection_member m ON m.collection_id = c.collection_id
            WHERE m.poem_id = ?
            ORDER BY c.sort_order
            LIMIT 5
            """,
            (poem_id,),
        ).fetchall()

    annotation = annotate.read(poem_id) if settings.annotations_enabled else None

    return PoemDetail(
        poem_id=row["poem_id"],
        title=row["title"],
        author=AuthorRef(
            author_id=row["author_id"], name=row["author_name"], dynasty=row["dynasty_name"]
        ),
        dynasty=row["dynasty_name"],
        period=row["period_name"],
        genre=row["genre"],
        form=row["form"],
        cipai=row["cipai"],
        content=row["content"],
        line_count=row["line_count"],
        char_count=row["char_count"],
        is_public_domain=bool(row["is_public_domain"]),
        collections=[CollectionRef(slug=c["slug"], name=c["name"]) for c in collections],
        annotation=annotation["annotation"] if annotation else None,
        translation=annotation["translation"] if annotation else None,
        appreciation=annotation["appreciation"] if annotation else None,
        annotation_background=annotation["background"] if annotation else None,
        annotation_citation=annotation["citation"] if annotation else None,
        annotation_sources=[
            AnnotationSource(title=s.get("title", ""), url=s.get("url", ""))
            for s in (annotation or {}).get("sources", [])
        ],
    )


def random_poem(filters: query.Filters) -> PoemDetail | None:
    """随机漫游 —— **均匀**采样。

    为什么不用「随机起点 poem_id + 取向后第一条命中」：那会让每首命中诗的概率
    ∝ 它前面那段 id 空洞的长度。语料的 `poem_id` 按来源/朝代成块连续分配，
    筛选后命中的是一整段，段首那首吞掉前面几十万个不属于本筛选的 id，
    概率被放大到几十个百分点（实测：朝代=明末清初 一首《琴河感旧 其一》占 97%）。

    **正确做法**：先 `COUNT` 得 N，再在 `[0, N)` 均匀取一个序号 `offset`，
    沿**已有的复合索引** `idx_poem_dynasty / genre / cipai` 的
    `(weight DESC, poem_id ASC)` 顺序取第 offset 首（合集走 `poem_id` 序，N 较小）。
    CTE 先只取 `poem_id`（覆盖索引跳过表行），再回表 join 拿详情，
    单次最坏约 30 ms（合集=全唐诗），比原算法既均匀又快。

    仍不用 `ORDER BY RANDOM()`：85 万行全表排序约 800 ms（docs/03 §3.4），
    而这里 COUNT + 一次索引定位是毫秒级。
    """
    import random

    clauses, params = filters.where()
    joins = ""
    if filters.collection_id is not None:
        joins = "JOIN collection_member cm ON cm.poem_id = p.poem_id"
        clauses.append("cm.collection_id = ?")
        params.append(filters.collection_id)
    where_sql = ("WHERE " + " AND ".join(clauses)) if clauses else ""
    # 合集没有 (weight, poem_id) 复合索引、N 又小，用 poem_id 序即可；
    # 其余维度（含无筛选）走 weight 序，复用 idx_poem_weight / idx_poem_{facet}。
    order_by = (
        "p.poem_id ASC" if filters.collection_id is not None
        else "p.weight DESC, p.poem_id ASC"
    )

    with db.read_cursor() as cur:
        total = count_only(filters)
        if total == 0:
            return None
        for _ in range(4):
            offset = random.randrange(total)
            row = cur.execute(
                f"""
                WITH hit AS (
                    SELECT p.poem_id
                    FROM poem p {joins} {where_sql}
                    ORDER BY {order_by}
                    LIMIT 1 OFFSET ?
                )
                {_SELECT} JOIN hit ON hit.poem_id = p.poem_id
                """,
                [*params, offset],
            ).fetchone()
            if row is not None:
                detail = fetch_detail(row["poem_id"])
                # `fetch_detail` 仅在 `INCLUDE_COPYRIGHTED=False` 且非公有领域时返回
                # None；那里已被 `where_sql` 排除，正常不会走到这里。重试是防御性的，
                # 避免偶发返回 null 被客户端误读为「范围内没有诗」。
                if detail is not None:
                    return detail
    return None


def estimate_pages(total: int, limit: int) -> int:
    return max(1, math.ceil(total / max(1, limit))) if total else 0

"""`/search`（`docs/02 §3.6` / `docs/03 §4.3`）。

核心场景是**「记得一句诗，找全诗」** —— 诗词类 App 最高频的搜索用法。因此：

1. 索引按 jieba 预分词建（FTS5 内置分词器都不支持中文）；
2. 排序：**标题精确命中 > 作者命中 > 正文命中；同档内按 `weight` 降序**。
   用户搜「李白」时想要的是李白的代表作，不是按 id 排的第一万首。
3. 返回 `snippet`（命中句及前后各一句）+ **字符区间**形式的 `highlights`。

**只承诺粗排序**（docs/02 §5.1）：服务端 FTS5+BM25、客户端 `LIKE`，
两者档内顺序本就不同；强行对齐意味着客户端也要引入 jieba 与倒排索引，代价远超收益。
"""

from __future__ import annotations

from fastapi import APIRouter, Query

from ..config import settings
from ..errors import ApiError
from ..schemas import SearchHit, SearchResponse
from ..services import fts as fts_service
from ..services import highlight as highlight_service
from ..services import query
from .. import db

router = APIRouter(tags=["search"])

#: `weight` 越高越靠前。`bm25` 返回的是「越小越相关」，故排序时取 ASC 再按档升序。
_TIER_SQL = """
SELECT f.rowid AS poem_id,
       bm25(poem_fts, :w_title, :w_author, :w_content) AS rank,
       p.weight, p.title, p.excerpt, p.content,
       a.name AS author, d.name AS dynasty,
       CASE
         WHEN p.title  LIKE :like  THEN 0
         WHEN a.name   LIKE :like  THEN 1
         ELSE 2
       END AS tier
FROM poem_fts f
JOIN poem    p ON p.poem_id = f.rowid
JOIN author  a ON a.author_id = p.author_id
JOIN dynasty d ON d.dynasty_id = p.dynasty_id
WHERE poem_fts MATCH :match AND {copyright}
ORDER BY tier ASC, rank ASC, p.weight DESC, p.poem_id ASC
LIMIT :limit
"""


@router.get("/search", response_model=SearchResponse)
def search(
    q: str = Query(..., description="查询串，1–64 字"),
    scope: str | None = Query(default=None, description="all / title / author / content"),
    limit: int = Query(default=20, ge=1, le=100),
):
    query_text = (q or "").strip()
    if not query_text:
        raise ApiError("INVALID_PARAM", "q 不能为空")
    if len(query_text) > settings.max_query_len:
        raise ApiError("INVALID_PARAM", f"q 长度不得超过 {settings.max_query_len}")

    if scope and scope not in ("all", "title", "author", "content"):
        raise ApiError("INVALID_PARAM", f"未知 scope: {scope}")

    # 单字查询（「月」「风」）在古诗里极常见，纯词级检索会大面积漏 → 走 LIKE 兜底
    if fts_service.needs_like_fallback(query_text):
        rows = _like_fallback(query_text, limit)
    else:
        match = fts_service.build_match(query_text, scope)
        if match is None:
            return SearchResponse(items=[], total=0, query=query_text)
        rows = _fts_search(match, query_text, limit)

    items: list[SearchHit] = []
    for row in rows:
        snippet, highlights = highlight_service.build_snippet(row["content"], query_text)
        items.append(
            SearchHit(
                poem_id=row["poem_id"],
                title=row["title"],
                author=row["author"],
                dynasty=row["dynasty"],
                hit=("title", "author", "content")[row["tier"]],
                snippet=snippet,
                highlights=highlights,
            )
        )

    return SearchResponse(items=items, next_cursor=None, total=len(items), query=query_text)


def _fts_search(match: str, raw_query: str, limit: int):
    sql = _TIER_SQL.format(copyright=settings.copyright_clause)
    with db.read_cursor() as cursor:
        return cursor.execute(
            sql,
            {
                "match": match,
                "like": f"%{raw_query}%",
                "w_title": settings.bm25_title,
                "w_author": settings.bm25_author,
                "w_content": settings.bm25_content,
                "limit": limit,
            },
        ).fetchall()


def _like_fallback(raw_query: str, limit: int):
    """零结果或纯单字查询时的 `LIKE` 兜底（docs/03 §4.4）。

    只扫 `title` 与前 200 字，控制代价；慢但只在必要时触发。
    """
    escaped = (
        raw_query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    )
    pattern = f"%{escaped}%"
    and_pd = "" if settings.include_copyrighted else "AND p.is_public_domain = 1"

    with db.read_cursor() as cursor:
        return cursor.execute(
            f"""
            SELECT p.poem_id,
                   0.0 AS rank,
                   p.weight, p.title, p.excerpt, p.content,
                   a.name AS author, d.name AS dynasty,
                   CASE
                     WHEN p.title LIKE :like ESCAPE '\\' THEN 0
                     WHEN a.name  LIKE :like ESCAPE '\\' THEN 1
                     ELSE 2
                   END AS tier
            FROM poem p
            JOIN author  a ON a.author_id  = p.author_id
            JOIN dynasty d ON d.dynasty_id = p.dynasty_id
            WHERE (
              p.title   LIKE :like ESCAPE '\\'
              OR a.name LIKE :like ESCAPE '\\'
              OR substr(p.content, 1, 200) LIKE :like ESCAPE '\\'
            ) {and_pd}
            ORDER BY tier ASC, p.weight DESC, p.poem_id ASC
            LIMIT :limit
            """,
            {"like": pattern, "limit": limit},
        ).fetchall()

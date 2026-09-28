"""`/poems`、`/poems/random`、`/poems/{id}`（docs/02 §3.3–3.5）。"""

from __future__ import annotations

from fastapi import APIRouter, Query

from ..errors import ApiError
from ..schemas import PoemDetail, PoemListResponse
from ..services import poems as poems_service
from ..services import query

router = APIRouter(tags=["poems"])


def _filters(
    period: str | None,
    dynasty: str | None,
    genre: str | None,
    cipai: str | None,
    author_id: int | None,
    collection: str | None,
    form: str | None,
) -> query.Filters:
    """参数 → Filters。`dynasty` 传的是 `/facets` 发布的 `key`（`tang`），
    `collection` 传的是 slug，二者都需查表换成库内 id。
    """
    dynasty_id = query.resolve_dynasty(dynasty)
    if dynasty and dynasty_id is None:
        raise ApiError("INVALID_PARAM", f"未知朝代 key: {dynasty}")
    collection_id = query.resolve_collection(collection)
    if collection and collection_id is None:
        raise ApiError("INVALID_PARAM", f"未知合集 slug: {collection}")

    return query.Filters(
        dynasty_id=dynasty_id,
        period=period,
        genre=genre,
        cipai=cipai,
        author_id=author_id,
        collection_id=collection_id,
        form=form,
    )


@router.get("/poems", response_model=PoemListResponse)
def list_poems(
    period: str | None = None,
    dynasty: str | None = None,
    genre: str | None = None,
    cipai: str | None = None,
    author_id: int | None = None,
    collection: str | None = None,
    form: str | None = None,
    sort: str = query.DEFAULT_SORT,
    cursor: str | None = None,
    limit: int = 20,
):
    if sort not in query.SORTS:
        raise ApiError("INVALID_PARAM", f"未知 sort: {sort}")

    filters = _filters(period, dynasty, genre, cipai, author_id, collection, form)
    items, next_cursor, total = poems_service.list_poems(filters, sort, cursor, limit, {})

    # 回显服务端**实际生效**的筛选，便于排查「我传了参数却没过滤」（docs/02 §3.3）
    applied = {
        k: v
        for k, v in {
            "period": period,
            "dynasty": dynasty,
            "genre": genre,
            "cipai": cipai,
            "author_id": str(author_id) if author_id is not None else None,
            "collection": collection,
            "form": form,
            "sort": sort,
        }.items()
        if v
    }
    return PoemListResponse(
        items=items, next_cursor=next_cursor, total=total, applied_filters=applied
    )


# ⚠️ `/poems/random` 必须注册在 `/poems/{poem_id}` **之前**：
# FastAPI 按注册顺序匹配，反了的话 "random" 会被当成 poem_id 走进 int 转换而 422。
@router.get("/poems/random", response_model=PoemDetail)
def random_poem(
    period: str | None = None,
    dynasty: str | None = None,
    genre: str | None = None,
    cipai: str | None = None,
    author_id: int | None = None,
    collection: str | None = None,
    form: str | None = None,
):
    filters = _filters(period, dynasty, genre, cipai, author_id, collection, form)
    poem = poems_service.random_poem(filters)
    if poem is None:
        raise ApiError("POEM_NOT_FOUND", "该筛选条件下没有可随机的诗")
    return poem


@router.get("/poems/{poem_id}", response_model=PoemDetail)
def get_poem(poem_id: int):
    poem = poems_service.fetch_detail(poem_id)
    if poem is None:
        raise ApiError("POEM_NOT_FOUND", f"poem_id {poem_id} 不存在")
    return poem

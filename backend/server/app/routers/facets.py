"""`/facets`（`docs/02 §3.7`）。

客户端启动时拉一次并长期缓存，用于渲染筛选面板。
**层级关系完全由服务端驱动**（`dynasties[].period`），客户端不硬编码
「大期 → 细分」的映射 —— `docs/01 §5.2` 的映射表是唯一真相源。
"""

from __future__ import annotations

from fastapi import APIRouter, Response

from ..config import settings
from ..schemas import CollectionFacet, FacetItem, FacetsResponse
from ..services import cache

router = APIRouter(tags=["facets"])


@router.get("/facets", response_model=FacetsResponse)
def get_facets(response: Response, refresh: bool = False):
    payload, generated_at = cache.get_or_compute(force=refresh)

    response.headers["Cache-Control"] = f"public, max-age={settings.facets_cache_seconds}"
    return FacetsResponse(
        periods=[FacetItem(**item) for item in payload["periods"]],
        dynasties=[FacetItem(**item) for item in payload["dynasties"]],
        genres=[FacetItem(**item) for item in payload["genres"]],
        cipais=[FacetItem(**item) for item in payload["cipais"]],
        collections=[CollectionFacet(**item) for item in payload["collections"]],
        generated_at=generated_at,
    )

"""`/poems/{id}/annotation` —— 只读注疏（`docs/06 §5`）。

**这里没有「生成」，也没有队列。** 需求纠正后注疏是预先从网络导入的真人内容
（`scripts/import_gushiwen.py`），所以端点退化成一次纯查表；
用户遇到「这首诗没有注疏」时的出路是方式一的流式问答（`routers/ask.py`），
不是在这个端点上等一分钟。
"""

from __future__ import annotations

from fastapi import APIRouter

from ..schemas import AnnotationResponse
from ..services import annotate

router = APIRouter(tags=["annotations"])


@router.get("/poems/{poem_id}/annotation", response_model=AnnotationResponse)
def get_annotation(poem_id: int):
    """读注疏。

    **不校验诗是否存在**：只查注疏库一次，为报 404 多查一次诗词库不划算；
    而客户端拿不到时会自然去走 `/poems/{id}`。
    """
    data = annotate.read(poem_id)
    if data is None:
        return AnnotationResponse(poem_id=poem_id, status="missing")
    return AnnotationResponse(
        poem_id=poem_id,
        status="ready",
        annotation=data["annotation"],
        translation=data["translation"],
        appreciation=data["appreciation"],
        background=data["background"],
        citation=data["citation"],
        sources=data["sources"],
        source=data["source"],
        license=data.get("license"),
    )

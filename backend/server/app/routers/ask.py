"""`POST /poems/{id}/ask` —— 方式一的流式问答端点（`docs/06 §6`）。

## 为什么是 SSE 而不是 WebSocket

需求是**单向**的：客户端发一个问题，服务端逐字推回答。WebSocket 提供了双向通道、
心跳、自定义帧——这些全用不上，却要承担握手、连接保活、代理兼容性一堆成本。
SSE 是普通 HTTP，`text/event-stream` 而已，穿透任何反代与移动网络。

## 为什么用 POST 而不是 GET + EventSource

浏览器的 `EventSource` 只支持 GET，但**我们的客户端是 Android**（OkHttp 直接读响应流），
不受这个限制。而问题文本放 query string 里，中文要编码、长度受限、还会进各种访问日志 ——
用 POST + JSON body 更干净。

## 事件协议

```
event: sources
data: {"items":[{"title":"…","url":"…"}],"searched":true}

event: delta
data: {"text":"幽"}

event: done
data: {"ok":true}
```

失败时给 `event: error` + `{"message":"…"}`。**异常一律变成事件而不是 HTTP 500** ——
流已经开始（状态码早已发出 200），此时只能靠事件通道上报。
"""

from __future__ import annotations

import json
import logging

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from ..errors import ApiError
from ..services import annotate
from ..services import ask
from ..services import poems as poems_service

logger = logging.getLogger("shici.ask")

router = APIRouter(tags=["ask"])

#: 反代常见做法是缓冲响应直到结束，那会把流式变成「憋几十秒再一次性吐出来」。
#: 显式禁用，并提示 nginx 不要 buffer。
_SSE_HEADERS = {
    "Cache-Control": "no-cache, no-transform",
    "Connection": "keep-alive",
    "X-Accel-Buffering": "no",
}


class AskRequest(BaseModel):
    """提问。`question` 长度由 `ask_max_question_chars` 限制。"""

    question: str = Field(min_length=1, description="用户关于这首诗的问题")
    #: 是否检索。默认走服务端配置；显式传 `false` 可用于「快速模式」或调试。
    search: bool | None = None


def _sse(event: str, data: dict) -> str:
    """SSE 帧。`ensure_ascii=False` 让中文直接以 UTF-8 出行，体积小且可读。"""
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


@router.post("/poems/{poem_id}/ask")
def ask_about_poem(poem_id: int, request: AskRequest):
    """就某首诗提问，流式回答。

    **校验诗存在**：问答要拼正文进提示词，诗不存在就没有上下文可给，
    此时应该 404 而不是让模型凭空回答。
    """
    question = request.question.strip()
    if not question:
        raise ApiError("INVALID_PARAM", "question 不能为空")
    if len(question) > ask.settings.ask_max_question_chars:
        raise ApiError(
            "INVALID_PARAM",
            f"question 过长（上限 {ask.settings.ask_max_question_chars} 字）",
        )

    poem = poems_service.fetch_detail(poem_id)
    if poem is None:
        raise ApiError("POEM_NOT_FOUND", f"poem_id {poem_id} 不存在")

    context = ask.PoemContext(
        title=poem.title,
        author=poem.author.name,
        dynasty=poem.dynasty,
        content=poem.content,
        # 本诗已有注疏就带上 —— 真人编辑内容，可信度高于检索结果。
        # 实测少了它，模型会把「怆然」的读音答成 chuáng rán（见 `ask.PoemContext` 的注释）。
        annotation=ask.compose_annotation(annotate.read(poem_id)),
    )
    use_search = ask.settings.ask_search_first if request.search is None else request.search

    def generate():
        """把 service 层的事件流翻译成 SSE 帧。

        ⚠️ **必须捕获所有异常**：生成器在响应头已发出之后才运行，
        此时抛异常客户端只会看到「连接被掐断」，看不到任何原因。
        """
        try:
            for event, payload in ask.answer(context, question, use_search=use_search):
                yield _sse(event, payload)
        except Exception as exc:  # noqa: BLE001
            logger.exception("问答流异常：%s", exc)
            yield _sse(ask.EVENT_ERROR, {"message": f"{type(exc).__name__}: {exc}"})

    return StreamingResponse(
        generate(),
        media_type="text/event-stream; charset=utf-8",
        headers=_SSE_HEADERS,
    )

"""统一错误模型（`docs/02 §1.4`）。

`message` 仅供开发期排查，**客户端不得依赖其内容**，一律按 `code` 分支。
"""

from __future__ import annotations

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

#: code → HTTP 状态码
HTTP_STATUS = {
    "INVALID_PARAM": 400,
    "POEM_NOT_FOUND": 404,
    "AUTHOR_NOT_FOUND": 404,
    "PACK_NOT_FOUND": 404,
    "RATE_LIMITED": 429,
    "INTERNAL": 500,
}


class ApiError(Exception):
    def __init__(self, code: str, message: str = "") -> None:
        super().__init__(message)
        self.code = code
        self.message = message or code


def _payload(code: str, message: str) -> dict:
    return {"error": {"code": code, "message": message}}


async def api_error_handler(_: Request, exc: ApiError) -> JSONResponse:
    return JSONResponse(
        status_code=HTTP_STATUS.get(exc.code, 500),
        content=_payload(exc.code, exc.message),
    )


async def validation_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    """FastAPI 的参数校验失败统一映射为 `INVALID_PARAM`。

    否则客户端会看到 FastAPI 默认的 422 + 它自己的结构，与契约不符。
    """
    first = exc.errors()[0] if exc.errors() else {}
    location = ".".join(str(part) for part in first.get("loc", []))
    return JSONResponse(
        status_code=400,
        content=_payload("INVALID_PARAM", f"{location}: {first.get('msg', '参数不合法')}"),
    )


async def internal_handler(_: Request, exc: Exception) -> JSONResponse:
    return JSONResponse(status_code=500, content=_payload("INTERNAL", str(exc)))

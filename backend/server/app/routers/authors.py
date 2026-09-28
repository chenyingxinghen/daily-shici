"""`/authors/{id}`（`docs/02 §3.8`）。

⚠️ **`author_id` 不可离线使用**：它是服务端分配的，离线包里只有作者名。
客户端因此必须按**归一化后的作者名**建立本地作者索引，而不是缓存 id。
这不构成不一致 —— 本表的 `author_id` 本就按归一化作者名分组，
包里写的也是同一个名字，故 `WHERE author = '李白'` 与 `?author_id=88` 返回同一组诗。
"""

from __future__ import annotations

from fastapi import APIRouter

from ..errors import ApiError
from ..schemas import AuthorResponse
from .. import db

router = APIRouter(tags=["authors"])


@router.get("/authors/{author_id}", response_model=AuthorResponse)
def get_author(author_id: int):
    with db.read_cursor() as cursor:
        row = cursor.execute(
            """
            SELECT a.author_id, a.name, a.poem_count, d.name AS dynasty
            FROM author a JOIN dynasty d ON d.dynasty_id = a.dynasty_id
            WHERE a.author_id = ?
            """,
            (author_id,),
        ).fetchone()

    if row is None:
        raise ApiError("AUTHOR_NOT_FOUND", f"author_id {author_id} 不存在")

    return AuthorResponse(
        author_id=row["author_id"],
        name=row["name"],
        dynasty=row["dynasty"],
        poem_count=row["poem_count"],
    )

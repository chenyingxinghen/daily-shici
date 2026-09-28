"""`/packs`（`docs/02 §3.9`）。

**必须联网** —— 客户端失败时退化为用本地已装记录渲染「已装」状态。

`download_url` 指向**静态文件，不经 API 服务**：包是 4–29 MB 的不可变文件，
交给 nginx/CDN 直接吐；若走 uvicorn 会占满 worker 并拖垮同进程的 API 请求。
"""

from __future__ import annotations

from fastapi import APIRouter

from ..config import settings
from ..schemas import PackItem, PacksResponse
from .. import db

router = APIRouter(tags=["packs"])


@router.get("/packs", response_model=PacksResponse)
def get_packs():
    with db.read_cursor() as cursor:
        packs = cursor.execute(
            "SELECT * FROM pack ORDER BY "
            "CASE tier WHEN 'L0' THEN 0 WHEN 'L1' THEN 1 WHEN 'L2' THEN 2 ELSE 3 END,"
            "poem_count DESC"
        ).fetchall()
        catalog_version = (
            cursor.execute(
                "SELECT COUNT(*) + COALESCE(MAX(version), 0) AS v FROM pack"
            ).fetchone()["v"]
            or 1
        )

    items: list[PackItem] = []
    for row in packs:
        members = _collections_of(row["pack_id"])
        items.append(
            PackItem(
                pack_id=row["pack_id"],
                name=row["name"],
                tier=row["tier"],
                version=row["version"],
                poem_count=row["poem_count"],
                bytes_gzip=row["bytes_gzip"],
                sha256=row["sha256"],
                min_poem_id=row["min_poem_id"],
                max_poem_id=row["max_poem_id"],
                builtin=bool(row["builtin"]),
                collections=members,
                download_url=f"/packs/{row['pack_id']}.v{row['version']}.jsonl.gz",
            )
        )

    return PacksResponse(catalog_version=int(catalog_version), packs=items)


def _collections_of(pack_id: str) -> list[str]:
    with db.read_cursor() as cursor:
        rows = cursor.execute(
            "SELECT slug FROM collection WHERE pack_id = ? ORDER BY sort_order", (pack_id,)
        ).fetchall()
    return [row["slug"] for row in rows]

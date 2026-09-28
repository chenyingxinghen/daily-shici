"""列表查询：动态 WHERE 拼接 + keyset 游标（`docs/03 §3`）。

这是全项目**最容易写错的一处 SQL**，原因是排序键方向不一致。
`ORDER BY weight DESC, poem_id ASC` 是**混合方向**，而 SQL 的行值比较
`(weight, poem_id) < (:w, :p)` 隐含两列同向，会让 `poem_id` 那半翻错方向
（表现为翻页时重复或整档跳过）。**必须显式拆成两个条件**：

    weight < :cw OR (weight = :cw AND poem_id > :cid)

游标**不签名**（docs/03 §3.2）：被篡改的后果只是用户看到错误的一页，无安全影响。
"""

from __future__ import annotations

import base64
import json
from dataclasses import dataclass

from .. import db
from ..config import settings
from . import slugs

# ---------------------------------------------------------------------------
# 游标
# ---------------------------------------------------------------------------


def encode_cursor(sort: str, values: tuple) -> str:
    """游标 = base64(`{"s": sort, "v": [...]}`)，对客户端不透明。

    带上 `sort` 是为了让「sort 变化时游标失效」（docs/02 §1.3）可以自动生效：
    解出来的 sort 与当前请求不一致就直接从头开始，而不是拼出一个无意义的 WHERE。
    """
    payload = json.dumps({"s": sort, "v": list(values)}, separators=(",", ":"))
    return base64.urlsafe_b64encode(payload.encode()).decode().rstrip("=")


def decode_cursor(cursor: str | None, expected_sort: str) -> tuple | None:
    """解不出或 sort 不匹配都返回 None（= 从头开始），不抛错。"""
    if not cursor:
        return None
    try:
        padded = cursor + "=" * (-len(cursor) % 4)
        payload = json.loads(base64.urlsafe_b64decode(padded).decode())
        if payload.get("s") != expected_sort:
            return None
        values = payload.get("v")
        return tuple(values) if isinstance(values, list) else None
    except Exception:
        return None


# ---------------------------------------------------------------------------
# 排序定义
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class SortSpec:
    """一种排序的完整定义：ORDER BY 子句 + 游标字段 + keyset 条件生成器。

    把三者绑在一个对象里，是为了让「加一种排序」时不会漏掉其中一个 ——
    漏掉 ORDER BY 会索引失效，漏掉 keyset 会翻页错乱，两者都不报错。
    """

    name: str
    order_by: str
    cursor_fields: tuple[str, ...]

    def keyset(self, cursor: tuple, alias: str = "p") -> tuple[str, list]:
        """生成 keyset 条件（不是 OFFSET）。"""
        if self.name == "weight":
            weight, poem_id = cursor
            return (
                f"({alias}.weight < ? OR ({alias}.weight = ? AND {alias}.poem_id > ?))",
                [weight, weight, poem_id],
            )
        if self.name == "id":
            (poem_id,) = cursor
            return (f"{alias}.poem_id > ?", [poem_id])
        if self.name == "dynasty":
            dynasty_order, weight, poem_id = cursor
            return (
                f"({alias}.dynasty_order > ? OR ({alias}.dynasty_order = ? AND "
                f"({alias}.weight < ? OR ({alias}.weight = ? AND {alias}.poem_id > ?))))",
                [dynasty_order, dynasty_order, weight, weight, poem_id],
            )
        raise ValueError(f"未知排序 {self.name}")

    def next_values(self, row) -> tuple:
        if self.name == "weight":
            return (row["weight"], row["poem_id"])
        if self.name == "id":
            return (row["poem_id"],)
        return (row["dynasty_order"], row["weight"], row["poem_id"])


#: 排序键定义。`order_by` 必须与 `schema.py` 里的索引**逐字同向**，否则索引失效。
SORTS: dict[str, SortSpec] = {
    "weight": SortSpec(
        name="weight",
        order_by="p.weight DESC, p.poem_id ASC",
        cursor_fields=("weight", "poem_id"),
    ),
    "id": SortSpec(
        name="id",
        order_by="p.poem_id ASC",
        cursor_fields=("poem_id",),
    ),
    # docs/02 §9.3 的语义未定（升序=时间线 / 降序=从近到远），此处按产品默认取升序
    "dynasty": SortSpec(
        name="dynasty",
        order_by="d.dynasty_order ASC, p.weight DESC, p.poem_id ASC",
        cursor_fields=("dynasty_order", "weight", "poem_id"),
    ),
}

DEFAULT_SORT = "weight"


# ---------------------------------------------------------------------------
# 筛选条件
# ---------------------------------------------------------------------------


@dataclass
class Filters:
    """`/poems` 的筛选参数（全部可选、可组合，docs/02 §3.3）。

    ⚠️ `period` / `dynasty` / `genre` / `cipai` 传的是**查表得到的 id 或字面量**，
    而不是中文名 —— 客户端负责把 `tang` 这样的 key 传上来。
    """

    dynasty_id: int | None = None
    period: str | None = None
    genre: str | None = None
    cipai: str | None = None
    author_id: int | None = None
    collection_id: int | None = None
    form: str | None = None

    def where(self) -> tuple[list[str], list]:
        """返回 (条件列表, 参数列表)。全程参数化占位符，无字符串拼接。"""
        clauses: list[str] = []
        params: list = []

        if self.dynasty_id is not None:
            clauses.append("p.dynasty_id = ?")
            params.append(self.dynasty_id)
        if self.period:
            clauses.append("d.period = ?")
            params.append(self.period)
        if self.genre:
            clauses.append("p.genre = ?")
            params.append(self.genre)
        if self.cipai:
            clauses.append("p.cipai = ?")
            params.append(self.cipai)
        if self.author_id is not None:
            clauses.append("p.author_id = ?")
            params.append(self.author_id)
        if self.form:
            clauses.append("p.form = ?")
            params.append(self.form)

        # 版权开关：唯一允许拼接的片段，值域只有两个字面量（docs/03 §5.4）
        clauses.append(settings.copyright_clause)

        return clauses, params

    def applied(self, **echo: str | None) -> dict[str, str]:
        """回显服务端实际生效的筛选，便于排查「我传了参数却没过滤」。"""
        return {k: v for k, v in echo.items() if v}


def resolve_dynasty(key: str | None) -> int | None:
    """`key` 是 `/facets` 发布的朝代 slug（`tang`），不是库里的中文名（`唐`）。

    契约（docs/02 §3.7，见 `slugs.py`）：`key` 供在线查询、`name` 供本地查询与展示。
    此前这里直接拿 `key` 去匹配 `dynasty.name`，于是 `dynasty=tang` 被 400「未知朝代 key」——
    这是后端自相矛盾的契约 bug。修法：先把 `key` 还原成中文名再查库，与
    `resolve_collection` 按 `slug` 解析保持同一套路。直接传中文名（唐）也能解析（见
    `slugs.dynasty_name` 的回退）。
    """
    if not key:
        return None
    name = slugs.dynasty_name(key)
    with db.read_cursor() as cursor:
        row = cursor.execute("SELECT dynasty_id FROM dynasty WHERE name = ?", (name,)).fetchone()
    return row["dynasty_id"] if row else None


def resolve_author(author_id: int | None) -> int | None:
    return author_id


def resolve_collection(slug: str | None) -> int | None:
    if not slug:
        return None
    with db.read_cursor() as cursor:
        row = cursor.execute(
            "SELECT collection_id FROM collection WHERE slug = ?", (slug,)
        ).fetchone()
    return row["collection_id"] if row else None

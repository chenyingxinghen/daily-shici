"""Pydantic 响应模型 —— 与 `docs/02-API契约.md §2` 逐字段对应。

**所有响应都经这里序列化**（`docs/03 §6`）。这样契约里的字段名与类型不会被某次
随手改动悄悄改掉 —— 客户端依赖的就是这些名字。

`annotation` / `translation` / `appreciation` 三个字段在 `PoemDetail` 上**仍然可空** ——
结构从 v1 起就预留，二期（`docs/06`）开始按诗填充：已生成的返回内容，
未收录的仍返 `null`。**客户端必须继续容忍 `null` 并隐藏区块**，
因为全量 83 万首里会有大量诗永远不生成注释（见 `docs/06 §4`）。
"""

from __future__ import annotations

from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# 错误
# ---------------------------------------------------------------------------


class ErrorBody(BaseModel):
    code: str
    message: str


class ErrorResponse(BaseModel):
    error: ErrorBody


# ---------------------------------------------------------------------------
# 诗词
# ---------------------------------------------------------------------------


class AuthorRef(BaseModel):
    author_id: int
    name: str
    dynasty: str


class CollectionRef(BaseModel):
    slug: str
    name: str


class PoemSummary(BaseModel):
    poem_id: int
    title: str
    author: str
    dynasty: str
    genre: str
    excerpt: str


class PoemDetail(BaseModel):
    poem_id: int
    title: str
    author: AuthorRef
    dynasty: str
    period: str
    genre: str
    form: str | None = None
    cipai: str | None = None
    content: str
    line_count: int
    char_count: int
    is_public_domain: bool
    collections: list[CollectionRef] = Field(default_factory=list)
    # 注疏。**未收录时为 null**，客户端必须隐藏对应区块（docs/02 §2.2 的原始约定）。
    # 内容来自从网络导入的真人编辑成果，不是模型生成 —— 见 docs/06。
    annotation: str | None = None
    translation: str | None = None
    appreciation: str | None = None
    #: **新增可选字段**（docs/02 §7：向后兼容的加法不需要升版本）。
    #: 创作背景。上游把它与「赏析」并列，内容性质确实不同（史实 vs 评论），故单列。
    annotation_background: str | None = None
    #: 文献出处（书目条目，如「王运熙 等．唐诗鉴赏辞典．上海：上海辞书出版社，1983：46-47」）。
    #: 这是**真人编辑内容的凭据**，也是用户去查原书的路标，不要当噪音删掉。
    annotation_citation: str | None = None
    #: 参考来源链接。目前指向古诗文网的站内检索页（上游数据没有原页 URL，见 docs/06 §2.2）。
    annotation_sources: list["AnnotationSource"] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# 注释（docs/06）
# ---------------------------------------------------------------------------


class AnnotationSource(BaseModel):
    """一个参考页面。客户端只做展示与跳转，不解析。"""

    title: str
    url: str


class AnnotationResponse(BaseModel):
    """`GET /poems/{id}/annotation`。

    只读。**没有「生成中 / 已排队」这类状态** —— 注疏是预先导入的真人内容，
    只有「有」与「没有」两种情况（`docs/06 §5`）。
    用户想要的「这首诗没有注疏怎么办」由方式一（流式问答）回答，不在这里。
    """

    poem_id: int
    #: `ready`（已收录）/ `missing`（未收录）。客户端按它决定展示注疏还是引导去提问。
    status: str
    annotation: str | None = None
    translation: str | None = None
    appreciation: str | None = None
    background: str | None = None
    citation: str | None = None
    sources: list[AnnotationSource] = Field(default_factory=list)
    #: 数据源标识（如 `gushiwen`）与许可，**必须回传** —— 这是别人编辑成果的出处声明。
    source: str | None = None
    license: str | None = None


class PoemListResponse(BaseModel):
    items: list[PoemSummary] = Field(default_factory=list)
    next_cursor: str | None = None
    total: int = 0
    applied_filters: dict[str, str] = Field(default_factory=dict)


class SearchHit(BaseModel):
    poem_id: int
    title: str
    author: str
    dynasty: str
    hit: str
    snippet: str
    #: 字符区间，左闭右开，**按 Unicode 码点计**
    highlights: list[list[int]] = Field(default_factory=list)


class SearchResponse(BaseModel):
    items: list[SearchHit] = Field(default_factory=list)
    next_cursor: str | None = None
    total: int = 0
    query: str = ""


class DailyResponse(BaseModel):
    date: str
    poem: PoemDetail
    pool_size: int


class DailyItem(BaseModel):
    date: str
    poem: PoemDetail


class DailyBatchResponse(BaseModel):
    items: list[DailyItem] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# 筛选器元数据
# ---------------------------------------------------------------------------


class FacetItem(BaseModel):
    key: str
    name: str
    count: int
    #: 仅 dynasties 有：所属大期 key
    period: str | None = None


class CollectionFacet(BaseModel):
    slug: str
    name: str
    count: int
    builtin: bool


class FacetsResponse(BaseModel):
    periods: list[FacetItem] = Field(default_factory=list)
    dynasties: list[FacetItem] = Field(default_factory=list)
    genres: list[FacetItem] = Field(default_factory=list)
    cipais: list[FacetItem] = Field(default_factory=list)
    collections: list[CollectionFacet] = Field(default_factory=list)
    generated_at: str


# ---------------------------------------------------------------------------
# 作者与数据包
# ---------------------------------------------------------------------------


class AuthorResponse(BaseModel):
    author_id: int
    name: str
    dynasty: str
    poem_count: int


class PackItem(BaseModel):
    pack_id: str
    name: str
    tier: str
    version: int
    poem_count: int
    bytes_gzip: int
    sha256: str
    min_poem_id: int
    max_poem_id: int
    builtin: bool
    collections: list[str] = Field(default_factory=list)
    download_url: str


class PacksResponse(BaseModel):
    catalog_version: int
    packs: list[PackItem] = Field(default_factory=list)

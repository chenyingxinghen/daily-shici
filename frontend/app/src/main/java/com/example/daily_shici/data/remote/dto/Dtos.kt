package com.example.daily_shici.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 网络 DTO。**逐字段对应 `docs/02-API契约.md` §2 / §3**，是前后端唯一分界。
 *
 * ⚠️ **两条硬规则，违反任何一条都会静默失效：**
 *
 * 1. **JSON 键名一律 snake_case**，属性名用 Kotlin 习惯的 camelCase，
 *    两者不同的字段**必须显式写 [SerialName]**。
 *    kotlinx-serialization 默认按属性名匹配，配上 `ignoreUnknownKeys = true`，
 *    名字对不上**不会报错，只会永远取默认值** ——
 *    表现为「编译通过、单测通过、真机一片空白」。
 * 2. **可空性要与契约一致**：契约里恒为 `null` 的字段（`annotation` 等）用可空类型，
 *    不要给非空默认值，否则二期服务端一填充就会被默认值吃掉。
 *
 * 所有集合字段都有默认值 —— 服务端少返一个字段不该让整页崩掉。
 */

// ---------------------------------------------------------------------------
// 通用
// ---------------------------------------------------------------------------

@Serializable
data class ErrorDto(val error: ErrorBodyDto? = null)

@Serializable
data class ErrorBodyDto(val code: String = "", val message: String = "")

/** 作者引用。**`PoemDetail.author` 是对象而非字符串**（docs/02 §2.2）。 */
@Serializable
data class AuthorRefDto(
    @SerialName("author_id") val authorId: Long = 0,
    val name: String = "",
    val dynasty: String = "",
)

/** 合集引用。`PoemDetail.collections` 是对象数组（docs/02 §2.2）。 */
@Serializable
data class CollectionRefDto(
    val slug: String = "",
    val name: String = "",
)

// ---------------------------------------------------------------------------
// 诗词
// ---------------------------------------------------------------------------

/** 列表用摘要（docs/02 §2.1）。`excerpt` = 正文第一行，列表只给这一句。 */
@Serializable
data class PoemSummaryDto(
    @SerialName("poem_id") val poemId: Long = 0,
    val title: String = "",
    val author: String = "",
    val dynasty: String = "",
    val genre: String = "",
    val excerpt: String = "",
)

/** 注释 / 译文 / 赏析的参考来源（docs/06）。纯展示 + 跳转，客户端不解析 URL。 */
@Serializable
data class AnnotationSourceDto(
    val title: String = "",
    val url: String = "",
)

/** 详情（docs/02 §2.2）。 */
@Serializable
data class PoemDetailDto(
    @SerialName("poem_id") val poemId: Long = 0,
    val title: String = "",
    val author: AuthorRefDto = AuthorRefDto(),
    val dynasty: String = "",
    val period: String = "",
    val genre: String = "",
    val form: String? = null,
    val cipai: String? = null,
    val content: String = "",
    @SerialName("line_count") val lineCount: Int = 0,
    @SerialName("char_count") val charCount: Int = 0,
    @SerialName("is_public_domain") val isPublicDomain: Boolean = true,
    /** 最多 5 个，按合集权重排序（docs/02 §2.2）。 */
    val collections: List<CollectionRefDto> = emptyList(),
    /**
     * 注疏（方式二）。**可空不是「暂时没数据」，是长期正常状态** ——
     * 全量 83.4 万首里只有 7,388 首有注疏（`docs/06 §3.3`），
     * 其余永远为 null。UI 必须隐藏区块，**不得渲染空标题**（`docs/02 §6.3`）。
     */
    val annotation: String? = null,
    val translation: String? = null,
    val appreciation: String? = null,
    /** 创作背景。与赏析并列但性质不同（史实 vs 评论），故单列。 */
    @SerialName("annotation_background") val annotationBackground: String? = null,
    /** 文献出处（书目条目）。是「真人编辑内容」的凭据，不是噪音。 */
    @SerialName("annotation_citation") val annotationCitation: String? = null,
    /** 来源链接，指向古诗文网站内检索页。 */
    @SerialName("annotation_sources") val annotationSources: List<AnnotationSourceDto> = emptyList(),
)

/**
 * `GET /poems/{id}/annotation`（`docs/06 §5`）。
 *
 * **只有 `ready` / `missing` 两种状态** —— 「生成中 / 已排队」那套随需求纠正移除了：
 * 注疏是预先导入的真人内容，不需要等；用户想要「这首诗没有注疏怎么办」的答案，
 * 由方式一的流式问答（[ShiciApi.ask]）给出。
 *
 * 各字段可空，且和 [PoemDetailDto] 一样：**空就是空，不要渲染空区块**。
 */
@Serializable
data class AnnotationDto(
    @SerialName("poem_id") val poemId: Long = 0,
    /** `ready`（已收录）/ `missing`（未收录）。未知取值按 `missing` 处理。 */
    val status: String = "",
    val annotation: String? = null,
    val translation: String? = null,
    val appreciation: String? = null,
    val background: String? = null,
    val citation: String? = null,
    val sources: List<AnnotationSourceDto> = emptyList(),
    /** 数据源与许可（如 `gushiwen` / `CC0-1.0`）—— 出处声明，UI 要显示出来。 */
    val source: String? = null,
    val license: String? = null,
)

/** 列表响应（docs/02 §3.3）。 */
@Serializable
data class PoemListResponseDto(
    val items: List<PoemSummaryDto> = emptyList(),
    /** keyset 游标。**不透明，客户端只回传不解析**；null 表示没有下一页。 */
    @SerialName("next_cursor") val nextCursor: String? = null,
    val total: Int = 0,
    /** 服务端实际生效的筛选条件，用于排查「我传了参数却没过滤」。 */
    @SerialName("applied_filters") val appliedFilters: Map<String, String> = emptyMap(),
)

// ---------------------------------------------------------------------------
// 搜索
// ---------------------------------------------------------------------------

/** 搜索命中（docs/02 §2.3）。**是扁平结构**，不是嵌套的 `poem` 对象。 */
@Serializable
data class SearchHitDto(
    @SerialName("poem_id") val poemId: Long = 0,
    val title: String = "",
    val author: String = "",
    val dynasty: String = "",
    /** 命中位置：`title` / `author` / `content`，客户端据此展示「命中标题」标识。 */
    val hit: String = "",
    /** 取命中句及其前后各一句，约 60 字。**搜索展示用 snippet 而不是 excerpt**。 */
    val snippet: String = "",
    /**
     * 字符区间 `[[start, end], …]`，左闭右开，**按 Unicode 码点计**。
     * 没有字段名 —— 契约里高亮只针对 `snippet`。
     */
    val highlights: List<List<Int>> = emptyList(),
)

@Serializable
data class SearchResponseDto(
    val items: List<SearchHitDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    val total: Int = 0,
    val query: String = "",
)

// ---------------------------------------------------------------------------
// 每日一诗
// ---------------------------------------------------------------------------

@Serializable
data class DailyItemDto(
    val date: String = "",
    val poem: PoemDetailDto = PoemDetailDto(),
)

/** `GET /daily`（docs/02 §3.1）。`pool_size` 用于诊断「为什么某天的诗很冷门」。 */
@Serializable
data class DailyResponseDto(
    val date: String = "",
    val poem: PoemDetailDto = PoemDetailDto(),
    @SerialName("pool_size") val poolSize: Int = 0,
)

/** `GET /daily/batch`（docs/02 §3.2）。 */
@Serializable
data class DailyBatchDto(
    val items: List<DailyItemDto> = emptyList(),
)

// ---------------------------------------------------------------------------
// 筛选器元数据
// ---------------------------------------------------------------------------

/** `periods` / `dynasties` / `genres` / `cipais` 共用的元素形状（docs/02 §3.7）。 */
@Serializable
data class FacetItemDto(
    /** 查询参数用的 key，如 `tang` / `shi`。 */
    val key: String = "",
    /** 展示与**本地查询**用的名字，如 `唐` / `诗`。 */
    val name: String = "",
    val count: Int = 0,
    /** 仅 `dynasties` 有：所属大期 key，客户端无需硬编码即可渲染两级筛选。 */
    val period: String? = null,
)

@Serializable
data class CollectionFacetDto(
    val slug: String = "",
    val name: String = "",
    val count: Int = 0,
    /** 是否在 L0 内置包内 —— 客户端据此显示「已内置」还是「需下载」。 */
    val builtin: Boolean = false,
)

@Serializable
data class FacetsDto(
    val periods: List<FacetItemDto> = emptyList(),
    val dynasties: List<FacetItemDto> = emptyList(),
    val genres: List<FacetItemDto> = emptyList(),
    val cipais: List<FacetItemDto> = emptyList(),
    val collections: List<CollectionFacetDto> = emptyList(),
    @SerialName("generated_at") val generatedAt: String = "",
)

// ---------------------------------------------------------------------------
// 作者
// ---------------------------------------------------------------------------

@Serializable
data class AuthorDto(
    @SerialName("author_id") val authorId: Long = 0,
    val name: String = "",
    val dynasty: String = "",
    @SerialName("poem_count") val poemCount: Int = 0,
)

// ---------------------------------------------------------------------------
// 数据包
// ---------------------------------------------------------------------------

@Serializable
data class PackDto(
    @SerialName("pack_id") val packId: String = "",
    val name: String = "",
    /** `L0`–`L3`，决定下载行为（静默 / 确认 / 强制 Wi-Fi），见 docs/01 §6.1。 */
    val tier: String = "L2",
    val version: Int = 1,
    @SerialName("poem_count") val poemCount: Int = 0,
    @SerialName("bytes_gzip") val bytesGzip: Long = 0,
    val sha256: String = "",
    @SerialName("min_poem_id") val minPoemId: Long = 0,
    @SerialName("max_poem_id") val maxPoemId: Long = 0,
    val builtin: Boolean = false,
    val collections: List<String> = emptyList(),
    /** 指向静态文件，**不经 API 服务**（docs/02 §3.9）。 */
    @SerialName("download_url") val downloadUrl: String = "",
)

@Serializable
data class PacksResponseDto(
    val packs: List<PackDto> = emptyList(),
    @SerialName("catalog_version") val catalogVersion: Int = 0,
)

// ---------------------------------------------------------------------------
// 离线包体（`packs/*.jsonl.gz` 的一行，docs/01 §6.2）
// ---------------------------------------------------------------------------

@Serializable
data class PackPoemDto(
    @SerialName("poem_id") val poemId: Long = 0,
    val title: String = "",
    val content: String = "",
    val author: String = "",
    val dynasty: String = "",
    val period: String = "",
    val genre: String = "",
    val form: String? = null,
    val cipai: String? = null,
    @SerialName("line_count") val lineCount: Int = 0,
    @SerialName("char_count") val charCount: Int = 0,
    @SerialName("is_public_domain") val isPublicDomain: Boolean = true,
    val weight: Int = 0,
)

package com.example.daily_shici.data.repository

import com.example.daily_shici.data.local.entity.AnnotationEntity
import com.example.daily_shici.data.local.entity.DailyCacheEntity
import com.example.daily_shici.data.local.entity.FavoriteEntity
import com.example.daily_shici.data.local.entity.HistoryEntity
import com.example.daily_shici.data.local.entity.PoemEntity
import com.example.daily_shici.data.remote.dto.AnnotationDto
import com.example.daily_shici.data.remote.dto.PackDto
import com.example.daily_shici.data.remote.dto.PoemDetailDto
import com.example.daily_shici.data.remote.dto.PoemSummaryDto
import com.example.daily_shici.domain.model.Annotation
import com.example.daily_shici.domain.model.AnnotationSource
import com.example.daily_shici.domain.model.DailyPoem
import com.example.daily_shici.domain.model.PackInfo
import com.example.daily_shici.domain.model.PoemDetail
import com.example.daily_shici.domain.model.PoemSummary
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 实体 / DTO / 领域模型之间的映射。
 *
 * 集中放一个文件是为了让「哪些字段在转换中丢了」一眼可见 —— 分散在各仓库里
 * 最容易出的问题是某个字段忘了映射，而且不报错、只是永远为空。
 */

/**
 * 摘要行取正文首行。入库时已算好存进 [PoemEntity.excerpt]，这里是网络路径的兜底。
 *
 * ⚠️ 契约的 `PoemSummary` **不含 `weight`**（docs/02 §2.1），所以网络来的摘要
 * `weight` 一律为 0。这不是缺陷：排序以本地包内的 `weight` 为准，
 * 在线列表的顺序由服务端按 `weight` 排好直接下发。
 */
fun deriveExcerpt(content: String): String =
    content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

fun deriveLineCount(content: String): Int = content.count { it == '\n' } + 1

fun deriveCharCount(content: String): Int = content.count { !it.isWhitespace() }

fun PoemEntity.toSummary(isDownloaded: Boolean = true) = PoemSummary(
    poemId = poemId,
    title = title,
    excerpt = excerpt,
    author = author,
    dynasty = dynasty,
    weight = weight,
    isDownloaded = isDownloaded,
)

fun PoemEntity.toDetail(
    collections: List<String> = emptyList(),
    annotation: Annotation? = null,
) = PoemDetail(
    poemId = poemId,
    title = title,
    content = content,
    author = author,
    dynasty = dynasty,
    period = period,
    genre = genre,
    form = form,
    cipai = cipai,
    lineCount = lineCount,
    charCount = charCount,
    collections = collections,
    annotation = annotation,
    isDownloaded = true,
)

fun PoemSummaryDto.toDomain(isDownloaded: Boolean = true) = PoemSummary(
    poemId = poemId,
    title = title,
    excerpt = excerpt,
    author = author,
    dynasty = dynasty,
    weight = 0,
    isDownloaded = isDownloaded,
)

fun PoemDetailDto.toDomain(isOfflineFallback: Boolean = false, isDownloaded: Boolean = true) = PoemDetail(
    poemId = poemId,
    title = title,
    content = content,
    // 契约里 author 是对象，取其中的 name；authorId 只用于在线调 /authors/{id}
    author = author.name,
    authorId = author.authorId,
    dynasty = dynasty,
    period = period,
    genre = genre,
    form = form,
    cipai = cipai,
    lineCount = if (lineCount > 0) lineCount else deriveLineCount(content),
    charCount = if (charCount > 0) charCount else deriveCharCount(content),
    // 契约里 collections 是对象数组，客户端只用显示名（详情页显示「收录于《…》」）
    collections = collections.map { it.name }.filter { it.isNotBlank() },
    annotation = toAnnotation(),
    isOfflineFallback = isOfflineFallback,
    isDownloaded = isDownloaded,
)

// ---------------------------------------------------------------------------
// 注释（docs/06）
// ---------------------------------------------------------------------------

/**
 * 注释来源的 JSON 编解码。用独立的 [Json] 实例而不是全局那个：
 * - 这里存的是**我们自己写的数据**，不是服务端契约，所以 `ignoreUnknownKeys`
 *   不是为了兼容服务端演进，而是为了将来加字段时老数据还能读出来。
 * - **解析失败必须返回空列表而不是抛异常**：缓存里一条坏 JSON 不该让详情页崩掉。
 */
private val annotationJson = Json { ignoreUnknownKeys = true }

/**
 * 落库用的来源结构。**故意不复用领域模型 [AnnotationSource]** ——
 * 领域模型是业务对象，不该为了「能塞进 SQLite 的一列」而背上 `@Serializable`。
 * 这两件事将来会各自演化（比如来源想加「抓取时间」，而领域模型不关心）。
 */
@Serializable
private data class SourceJson(val title: String = "", val url: String = "")

/**
 * 详情 DTO → [Annotation]。所有区块都空时返回 `null` ——
 * 把「服务端没给」和「服务端给了几个空串」收敛成同一种状态，
 * UI 只需要判断 `null`，不必再判一次 `isEmpty`。
 */
fun PoemDetailDto.toAnnotation(): Annotation? {
    val built = Annotation(
        annotation = annotation?.takeIf { it.isNotBlank() },
        translation = translation?.takeIf { it.isNotBlank() },
        appreciation = appreciation?.takeIf { it.isNotBlank() },
        background = annotationBackground?.takeIf { it.isNotBlank() },
        citation = annotationCitation?.takeIf { it.isNotBlank() },
        sources = annotationSources.map { AnnotationSource(it.title, it.url) },
    )
    return built.takeUnless { it.isEmpty }
}

fun AnnotationDto.toDomain(): Annotation? {
    val built = Annotation(
        annotation = annotation?.takeIf { it.isNotBlank() },
        translation = translation?.takeIf { it.isNotBlank() },
        appreciation = appreciation?.takeIf { it.isNotBlank() },
        background = background?.takeIf { it.isNotBlank() },
        citation = citation?.takeIf { it.isNotBlank() },
        sources = sources.map { AnnotationSource(it.title, it.url) },
        source = source,
        license = license,
    )
    return built.takeUnless { it.isEmpty }
}

fun AnnotationEntity.toDomain(): Annotation? {
    val sources = runCatching {
        annotationJson.decodeFromString<List<SourceJson>>(sourcesJson.orEmpty())
    }.getOrElse { emptyList() }.map { AnnotationSource(it.title, it.url) }
    val built = Annotation(
        annotation = annotation?.takeIf { it.isNotBlank() },
        translation = translation?.takeIf { it.isNotBlank() },
        appreciation = appreciation?.takeIf { it.isNotBlank() },
        background = background?.takeIf { it.isNotBlank() },
        citation = citation?.takeIf { it.isNotBlank() },
        sources = sources,
        source = source,
        license = license,
    )
    return built.takeUnless { it.isEmpty }
}

fun Annotation.toEntity(poemId: Long, nowMillis: Long) = AnnotationEntity(
    poemId = poemId,
    annotation = annotation,
    translation = translation,
    appreciation = appreciation,
    background = background,
    citation = citation,
    // 编码失败就存 null，代价只是下次打开详情页时来源区块为空 ——
    // 比整条注疏因为一个 URL 写不进去而丢失，划算得多。
    sourcesJson = runCatching {
        annotationJson.encodeToString(sources.map { SourceJson(it.title, it.url) })
    }.getOrNull(),
    source = source,
    license = license,
    cachedAt = nowMillis,
)

fun PackDto.toDomain(installedVersion: Int?) = PackInfo(
    packId = packId,
    name = name.ifBlank { packId },
    // 契约的包对象**没有 description**，这里留空由 UI 用「首数 · 体积 · 层级」表达
    description = "",
    poemCount = poemCount,
    bytesGzip = bytesGzip,
    builtin = builtin,
    tier = tier,
    installedVersion = installedVersion,
    latestVersion = version,
    url = downloadUrl,
    sha256 = sha256,
)

fun DailyCacheEntity.toDomain(): DailyPoem = DailyPoem(
    date = LocalDate.parse(date),
    poem = PoemDetail(
        poemId = poemId,
        title = title,
        content = content,
        author = author,
        dynasty = dynasty,
        collections = collectionsJson
            ?.trim()
            ?.removeSurrounding("[", "]")
            ?.split(',')
            ?.map { it.trim().trim('"') }
            ?.filter { it.isNotEmpty() }
            .orEmpty(),
        isOfflineFallback = isOfflineFallback,
        isDownloaded = true,
    ),
    isOfflineFallback = isOfflineFallback,
)

fun DailyPoem.toCacheEntity(collectionsJson: String? = null) = DailyCacheEntity(
    date = date.toString(),
    poemId = poem.poemId,
    title = poem.title,
    author = poem.author,
    dynasty = poem.dynasty,
    content = poem.content,
    collectionsJson = collectionsJson,
    isOfflineFallback = isOfflineFallback,
)

fun favoriteOf(poemId: Long, nowMillis: Long) = FavoriteEntity(poemId = poemId, createdAt = nowMillis)

fun bumpedHistory(existing: HistoryEntity?, poemId: Long, nowMillis: Long): HistoryEntity =
    existing?.copy(lastReadAt = nowMillis, readCount = existing.readCount + 1)
        ?: HistoryEntity(poemId = poemId, lastReadAt = nowMillis, readCount = 1)

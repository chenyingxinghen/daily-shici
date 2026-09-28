package com.example.daily_shici.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 诗词主表。索引与查询模式逐一对应（04 文档 §2.2）。
 *
 * ⚠️ Room 的 [Index] 不支持列级排序方向。服务端索引是 `(weight DESC, poem_id ASC)`，
 * 而这里建的索引一律升序。SQLite 可以反向扫描索引满足 `ORDER BY weight DESC`，
 * 但反向扫描时 `poemId` 也一并变 DESC，与契约要求的 `poemId ASC` 相反。
 *
 * 因此**所有列表查询都必须显式写 `ORDER BY weight DESC, poemId ASC`**，
 * 接受 SQLite 无法完全用索引满足排序。2 万行规模下代价可忽略。
 */
@Entity(
    tableName = "poem",
    indices = [
        Index(value = ["weight", "poemId"]),
        Index(value = ["dynasty", "weight", "poemId"]),
        Index(value = ["period", "weight", "poemId"]),
        Index(value = ["genre", "weight", "poemId"]),
        Index(value = ["cipai", "weight", "poemId"]),
        Index(value = ["author", "weight", "poemId"]),
        Index(value = ["packId"]),
    ]
)
data class PoemEntity(
    @PrimaryKey val poemId: Long,
    val title: String,
    /** `\n` 是真实换行，包内是 `\\n` 转义，JSON 解析后自然还原，无需额外处理。 */
    val content: String,
    /** 首行，列表用。入库时算一次，避免每次列表查询都切字符串。 */
    val excerpt: String,
    /** 归一化作者名 —— 本地导航键就是它，不是服务端的 author_id。 */
    val author: String,
    val dynasty: String,
    val period: String,
    val genre: String,
    val form: String? = null,
    val cipai: String? = null,
    val lineCount: Int,
    val charCount: Int,
    val isPublicDomain: Boolean = true,
    val weight: Int = 0,
    /** 来源包。卸载包时按此批量删除，也是「未下载」判定的依据。 */
    val packId: String,
)

/**
 * 收藏。纯本地，`userId` / `syncedAt` 是二期账号同步的预留字段，
 * **v1 不写任何读取它们的逻辑**，只保证存在。
 */
@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey val poemId: Long,
    val createdAt: Long,
    val userId: String? = null,
    val syncedAt: Long? = null,
)

/** 阅读历史。同上，纯本地 + 预留同步字段。 */
@Entity(tableName = "reading_history")
data class HistoryEntity(
    @PrimaryKey val poemId: Long,
    val lastReadAt: Long,
    val readCount: Int = 1,
    val userId: String? = null,
    val syncedAt: Long? = null,
)

/** 数据包安装状态。对应 01 §6.3 的 manifest。 */
@Entity(tableName = "installed_pack")
data class InstalledPackEntity(
    @PrimaryKey val packId: String,
    val version: Int,
    val poemCount: Int,
    val sha256: String,
    val installedAt: Long,
    val bytesGzip: Long,
)

/**
 * 每日一诗缓存。
 *
 * [content] 必须存正文 —— 这是「离线可读」的根据，只存 poemId 的缓存在断网时是废的。
 */
@Entity(tableName = "daily_cache")
data class DailyCacheEntity(
    @PrimaryKey val date: String, // YYYY-MM-DD
    val poemId: Long,
    val title: String,
    val author: String,
    val dynasty: String,
    val content: String,
    val collectionsJson: String? = null,
    val isOfflineFallback: Boolean = false,
)

/** 合集（内置 + 已下载包带来的）。 */
@Entity(tableName = "collection")
data class CollectionEntity(
    @PrimaryKey val slug: String,
    val name: String,
    /** NULL = 未下载。 */
    val packId: String? = null,
    val builtin: Boolean = false,
    val sortOrder: Int = 0,
)

/**
 * 注疏的**本地缓存**（`docs/06`）。
 *
 * 为什么值得单独缓存：注疏是**从网络导入的真人编辑成果**，不是每次都能现取 ——
 * 服务端只有 7,388 首有（占全量 0.88%），而客户端离线时它得还在，
 * 否则「本地优先」这个产品定位就漏了一块。
 *
 * 只存**已成功取到**的结果。没注疏的诗不建行 —— 用「有没有这一行」
 * 表达「有没有注疏」，避免再搞一个 `status` 字段并引入「有行但无内容」的第三种状态。
 */
@Entity(tableName = "poem_annotation")
data class AnnotationEntity(
    @PrimaryKey val poemId: Long,
    val annotation: String? = null,
    val translation: String? = null,
    val appreciation: String? = null,
    /** 创作背景。 */
    val background: String? = null,
    /** 文献出处（书目条目）。 */
    val citation: String? = null,
    /** 参考来源，JSON 数组字符串。纯展示用，客户端不解析成域名做任何判断。 */
    val sourcesJson: String? = null,
    /** 数据源与许可（`gushiwen` / `CC0-1.0`）。**出处声明，展示时要带上。** */
    val source: String? = null,
    val license: String? = null,
    val cachedAt: Long,
)

@Entity(tableName = "collection_member", primaryKeys = ["collectionId", "poemId"])
data class CollectionMemberEntity(
    val collectionId: String,
    val poemId: Long,
)

/**
 * 筛选器元数据缓存。单行表（id 恒为 1）。
 *
 * 存的是服务端 `/facets` 的原始 JSON。用它判断「应有条数」，
 * 从而区分「真的没有」与「该内容未下载」。
 */
@Entity(tableName = "facets_cache")
data class FacetsCacheEntity(
    @PrimaryKey val id: Int = 1,
    val payloadJson: String,
    val generatedAt: Long,
)

/** 数据包内的一行诗词（01 §6.2 的反范式化格式：作者/朝代直接是名字）。 */
data class PackPoemRow(
    val poemId: Long,
    val title: String,
    val content: String,
    val author: String,
    val dynasty: String,
    val period: String = "",
    val genre: String = "",
    val form: String? = null,
    val cipai: String? = null,
    val lineCount: Int = 0,
    val charCount: Int = 0,
    val isPublicDomain: Boolean = true,
    val weight: Int = 0,
) {
    fun toEntity(packId: String, derivedExcerpt: String, derivedLineCount: Int, derivedCharCount: Int) =
        PoemEntity(
            poemId = poemId,
            title = title,
            content = content,
            excerpt = derivedExcerpt,
            author = author,
            dynasty = dynasty,
            period = period,
            genre = genre,
            form = form,
            cipai = cipai,
            lineCount = if (lineCount > 0) lineCount else derivedLineCount,
            charCount = if (charCount > 0) charCount else derivedCharCount,
            isPublicDomain = isPublicDomain,
            weight = weight,
            packId = packId,
        )
}

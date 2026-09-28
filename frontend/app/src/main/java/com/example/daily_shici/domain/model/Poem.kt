package com.example.daily_shici.domain.model

/**
 * 列表用的诗词摘要。对应服务端 `/poems` 与 `/search` 的条目。
 *
 * [isDownloaded] 是**客户端本地概念**：服务端返回的 `poemId` 若不在本地 poem 表，
 * 就是「未下载」。设计上是显式状态，不允许靠空列表蒙混（见 04 文档 §4.2）。
 */
data class PoemSummary(
    val poemId: Long,
    val title: String,
    val excerpt: String,
    val author: String,
    val dynasty: String,
    val weight: Int = 0,
    val isDownloaded: Boolean = true,
)

/** 参考来源。只用于展示与跳转。 */
data class AnnotationSource(
    val title: String,
    val url: String,
)

/**
 * 注疏（方式二）。各字段**同时产出**，所以合起来当一个整体，
 * 而不是在 [PoemDetail] 上摊成五个可空字段 —— 那样很容易写出
 * 「有译文但不知道有没有注释」的判断，也容易漏判其中一个。
 */
data class Annotation(
    val annotation: String? = null,
    val translation: String? = null,
    val appreciation: String? = null,
    /** 创作背景。与 [appreciation] 并列但性质不同（史实 vs 评论），故单列。 */
    val background: String? = null,
    /** 文献出处（书目条目，如「王运熙 等．唐诗鉴赏辞典…」）。是内容的凭据，要展示。 */
    val citation: String? = null,
    val sources: List<AnnotationSource> = emptyList(),
    /** 数据源与许可，如 `gushiwen` / `CC0-1.0`。**出处声明，必须显示。** */
    val source: String? = null,
    val license: String? = null,
) {
    /** 所有正文区块都空等于没有。**不允许渲染空区块**（`docs/02 §6.3`）。 */
    val isEmpty: Boolean
        get() = listOf(annotation, translation, appreciation, background).all { it.isNullOrBlank() }

    /** 按展示顺序列出有内容的区块。UI 直接遍历它，不必写一串 if。 */
    val sections: List<Pair<String, String>>
        get() = buildList {
            annotation?.takeIf { it.isNotBlank() }?.let { add("注释" to it) }
            translation?.takeIf { it.isNotBlank() }?.let { add("译文" to it) }
            background?.takeIf { it.isNotBlank() }?.let { add("创作背景" to it) }
            appreciation?.takeIf { it.isNotBlank() }?.let { add("赏析" to it) }
        }
}

/** 详情页用的完整诗词。 */
data class PoemDetail(
    val poemId: Long,
    val title: String,
    val content: String,
    /** 作者显示名。**本地导航键就是它**，不是 `authorId`（docs/02 §3.8）。 */
    val author: String,
    /**
     * 服务端分配的作者 id，**仅用于在线调 `/authors/{id}`**。
     * 离线包里没有它（`docs/01 §6.2` 的包格式只写作者名），故本地查询一律按名字。
     */
    val authorId: Long = 0,
    val dynasty: String,
    val period: String = "",
    val genre: String = "",
    val form: String? = null,
    val cipai: String? = null,
    val lineCount: Int = content.count { it == '\n' } + 1,
    val charCount: Int = content.count { !it.isWhitespace() },
    val collections: List<String> = emptyList(),
    /**
     * 注疏。**null 是常态**：全量 83.4 万首里只有 7,388 首有注疏（`docs/06 §3.3`）。
     * 没有注疏时 UI 应当引导用户去「问一问」（方式一），而不是显示「加载失败」。
     */
    val annotation: Annotation? = null,
    val isOfflineFallback: Boolean = false,
    val isDownloaded: Boolean = true,
) {
    /** 「唐 · 李白」这类署名行。 */
    val attribution: String get() = listOf(dynasty, author).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * 已生效的筛选条件。
 *
 * 每个维度存的是 [FacetOption]（**key + label 双份**）而不是一个裸字符串：
 * 在线查询要 `tang`、本地 Room 查询要 `唐`，只存一个必然有一边查不到东西。
 * 详见 [FacetOption] 的注释。
 */
data class AppliedFilters(
    val dynasty: FacetOption? = null,
    val genre: FacetOption? = null,
    val cipai: FacetOption? = null,
    val collection: FacetOption? = null,
    val query: String? = null,
) {
    val isEmpty: Boolean
        get() = dynasty == null && genre == null && cipai == null &&
            collection == null && query == null

    /** 本地 Room 查询用的中文名组合。 */
    val localDynasty: String? get() = dynasty?.label
    val localGenre: String? get() = genre?.label
    val localCipai: String? get() = cipai?.label

    /**
     * 合集本地用 **key（slug）** 而不是 label —— `collection_member.collectionId`
     * 存的是 slug，中文名只在 `collection.name` 里。
     * 这是 [localDynasty] 那几条的例外，别顺手改成 `label`。
     */
    val localCollection: String? get() = collection?.key
}

/**
 * 浏览页的数据来源。
 *
 * 显式切换而**不让仓库层隐式替用户决定**：本地只有已下载的子集，
 * 服务端是全量 83 万首，两者的结果数差三个数量级。若静默选路，
 * 用户会以为「选了词牌怎么只有 3 首」，实际是他没下载那个包。
 */
enum class BrowseSource(val label: String) {
    ONLINE("在线全量"),
    LOCAL("已下载");
}

/**
 * 列表查询结果。**禁止用空列表同时表示「没有」和「没下载」**（04 文档 §4.2）——
 * 这两件事对用户的行动指引完全相反。
 */
sealed interface ListResult {
    data class Ok(val items: List<PoemSummary>) : ListResult

    /** 本地为 0，但服务端统计显示该条件下应有内容 → 提示下载对应数据包。 */
    data class NotDownloaded(val packIds: List<String>) : ListResult

    /** 真的没有匹配内容。 */
    data class Empty(val filters: AppliedFilters) : ListResult
}

/** 数据包（服务端清单 + 本地安装状态的合并视图）。 */
data class PackInfo(
    val packId: String,
    val name: String,
    val description: String,
    val poemCount: Int,
    val bytesGzip: Long,
    val builtin: Boolean = false,
    /** `L0`–`L3`。决定下载行为：L1 静默、L2 展示体积确认、L3 强制 Wi-Fi + 二次确认。 */
    val tier: String = "L2",
    val installedVersion: Int? = null,
    val latestVersion: Int = 1,
    /** 下载地址与校验值。带上它们是为了让 UI 能直接把包交给下载任务，不必回查清单。 */
    val url: String = "",
    val sha256: String = "",
) {
    val isInstalled: Boolean get() = installedVersion != null
    val needsUpdate: Boolean get() = installedVersion != null && installedVersion < latestVersion
    val canUninstall: Boolean get() = isInstalled && !builtin
}

/** 内置/已下载合集。 */
data class Collection(
    val slug: String,
    val name: String,
    val builtin: Boolean = false,
    val downloaded: Boolean = false,
)

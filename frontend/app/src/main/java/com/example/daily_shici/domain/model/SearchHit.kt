package com.example.daily_shici.domain.model

/**
 * 高亮区间。**索引单位是 Unicode 码点**，不是 Kotlin `String` 的 UTF-16 char ——
 * 这是与服务端 `/search` 的契约（02 §2.3）。转换入口在 `domain/search/SearchText`。
 */
data class HighlightRange(val start: Int, val end: Int) {
    init {
        require(start <= end) { "start($start) 不能大于 end($end)" }
    }
}

/**
 * 一条搜索命中。
 *
 * 两路数据源（在线 `/search`、离线本地 LIKE）产出的是**同一个数据结构**，
 * 所以 UI 层不需要知道结果从哪来 —— 这是把它定义成纯数据而非标记串的收益。
 *
 * ⚠️ **高亮只针对 [snippet]**：契约（`docs/02 §2.3`）返回的 `highlights` 是
 * `snippet` 内的字符区间，**不带字段名**。早先的实现按「标题/摘要分别高亮」设计，
 * 与契约不符，已改正。
 */
data class SearchHit(
    val poem: PoemSummary,
    /** 命中句及其前后各一句，约 60 字。搜索展示用**它**而不是 `poem.excerpt`。 */
    val snippet: String,
    val highlights: List<HighlightRange> = emptyList(),
    /** 命中位置：`title` / `author` / `content`（docs/02 §2.3）。 */
    val hitField: String = "content",
) {
    /** 命中标题时客户端会打一个标记，帮助用户理解「为什么这条排在前面」。 */
    val isTitleHit: Boolean get() = hitField == "title"
}

/** 搜索来源，UI 需要据此提示「仅在已下载内容中搜索」。 */
enum class SearchSource { REMOTE, LOCAL_CACHE, LOCAL_ONLY }

data class SearchOutcome(
    val hits: List<SearchHit>,
    val source: SearchSource,
    /** 命中但本地没有正文的条数，用于提示「N 首未下载」。 */
    val notDownloadedCount: Int = 0,
)

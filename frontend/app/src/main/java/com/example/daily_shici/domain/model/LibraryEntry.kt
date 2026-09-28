package com.example.daily_shici.domain.model

/**
 * 收藏 / 历史列表的一项。
 *
 * [isDownloaded] 为假表示用户卸掉了对应数据包 —— UI 显示灰态 + 「重新下载」入口，
 * 但**不删除这条记录**。这是 favorite 表与 poem 表分离的直接好处。
 */
data class LibraryEntry(
    val poemId: Long,
    val title: String,
    val excerpt: String,
    val author: String,
    val dynasty: String,
    val isDownloaded: Boolean,
    /** 收藏时间或最后阅读时间。 */
    val timestamp: Long,
    val readCount: Int = 0,
) {
    val attribution: String
        get() = listOf(dynasty, author).filter { it.isNotBlank() }.joinToString(" · ")

    /** 未下载且没有本地正文时，列表只能显示一个占位标题。 */
    val displayTitle: String get() = title.ifBlank { "（未下载）" }
}

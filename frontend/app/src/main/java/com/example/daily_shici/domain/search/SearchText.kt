package com.example.daily_shici.domain.search

import com.example.daily_shici.domain.model.HighlightRange

/**
 * 搜索文本处理：LIKE 转义 + 码点/UTF-16 索引换算 + 本地命中区间计算。
 *
 * 单独成文件是因为这三件事都是「平时全对、只有生僻字或带通配符的输入才出错」的类型，
 * 出问题时极难在人工点击中发现，必须靠单测锁住。
 */
object SearchText {

    private const val ESCAPE_CHAR = '\\'

    /**
     * 转义 SQL `LIKE` 的元字符。
     *
     * 不转义的后果：用户输入 `%` 会匹配全部诗词，输入 `_` 会匹配任意单字。
     * 反斜杠必须**最先**替换，否则会把后面新加的转义符再转义一次。
     */
    fun escapeLike(raw: String): String = buildString(raw.length + 8) {
        for (ch in raw) {
            when (ch) {
                ESCAPE_CHAR, '%', '_' -> {
                    append(ESCAPE_CHAR)
                    append(ch)
                }
                else -> append(ch)
            }
        }
    }

    /** 生成 `%关键词%` 形式的匹配串；调用方 SQL 里必须带 `ESCAPE '\'`。 */
    fun containsPattern(raw: String): String = "%${escapeLike(raw)}%"

    /**
     * 契约索引（码点）→ Kotlin String 索引（UTF-16 code unit）。
     * 用于渲染服务端返回的 `highlights`。
     */
    fun codePointToCharIndex(text: String, codePointIndex: Int): Int {
        val clamped = codePointIndex.coerceIn(0, text.codePointCount(0, text.length))
        return text.offsetByCodePoints(0, clamped)
    }

    /**
     * Kotlin String 索引（UTF-16 code unit）→ 码点索引。
     * 用于把本地 LIKE 算出的位置转成契约格式。
     */
    fun charIndexToCodePoint(text: String, charIndex: Int): Int {
        val clamped = charIndex.coerceIn(0, text.length)
        return text.codePointCount(0, clamped)
    }

    /**
     * 本地搜索时计算全部命中区间（码点单位，左闭右开，大小写不敏感）。
     *
     * 用 `indexOf` 逐段推进而不是正则 —— 关键词里可能含正则元字符（`(`、`*` 等），
     * 走正则就得再写一层转义，得不偿失。
     */
    fun highlightsOf(text: String, query: String): List<HighlightRange> {
        if (query.isEmpty() || text.isEmpty()) return emptyList()
        val result = mutableListOf<HighlightRange>()
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        var from = 0
        while (true) {
            val found = lowerText.indexOf(lowerQuery, from)
            if (found < 0) break
            result += HighlightRange(
                start = charIndexToCodePoint(text, found),
                end = charIndexToCodePoint(text, found + query.length)
            )
            // 步进一个字符而不是一个关键词长度，允许重叠命中（如 "月月" 里搜 "月"）
            from = found + 1
        }
        return result
    }

    /** 是否命中标题或作者 —— 对应服务端 tier 0/1 的「标题 > 作者」粗排序。 */
    fun matchesTitleOrAuthor(title: String, author: String, query: String): Boolean {
        val q = query.lowercase()
        return title.lowercase().contains(q) || author.lowercase().contains(q)
    }
}

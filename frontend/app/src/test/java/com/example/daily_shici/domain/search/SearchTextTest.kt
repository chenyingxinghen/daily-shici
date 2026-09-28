package com.example.daily_shici.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这些用例锁的都是「平时全对、只有特定输入才出错」的行为。
 * 没有它们，`%` 通配符、生僻字高亮偏移这类 bug 只能靠用户偶然碰到才发现。
 */
class SearchTextTest {

    /** 生僻字样本：U+2000B 在 UTF-16 里是代理对（2 个 char = 1 个码点）。 */
    private val rareText = "\uD840\uDC0B月照"

    @Test
    fun `转义 LIKE 元字符`() {
        assertEquals("100\\%", SearchText.escapeLike("100%"))
        assertEquals("a\\_b", SearchText.escapeLike("a_b"))
        // 反斜杠必须最先替换，否则会把新加的转义符再转义一次
        assertEquals("\\\\", SearchText.escapeLike("\\"))
        assertEquals("\\%\\_\\\\", SearchText.escapeLike("%_\\"))
        // 普通汉字不受影响
        assertEquals("明月", SearchText.escapeLike("明月"))
    }

    @Test
    fun `containsPattern 包裹百分号`() {
        assertEquals("%明月%", SearchText.containsPattern("明月"))
        assertEquals("%100\\%%", SearchText.containsPattern("100%"))
    }

    @Test
    fun `码点与 UTF-16 索引互转 - 生僻字`() {
        // U+2000B 是代理对：占 2 个 char、算 1 个码点；再接「月」「照」两个 BMP 字符
        assertEquals(4, rareText.length)
        assertEquals(3, rareText.codePointCount(0, rareText.length))

        // 第 1 个码点之后是第 2 个 char（代理对整体算一个）
        assertEquals(2, SearchText.codePointToCharIndex(rareText, 1))
        assertEquals(1, SearchText.charIndexToCodePoint(rareText, 2))
        // 第 2 个码点（月）落在 char 索引 2..3
        assertEquals(3, SearchText.codePointToCharIndex(rareText, 2))
        assertEquals(2, SearchText.charIndexToCodePoint(rareText, 3))
    }

    @Test
    fun `索引换算对越界输入收敛而不是抛异常`() {
        assertEquals(0, SearchText.codePointToCharIndex("明月", -5))
        assertEquals(2, SearchText.codePointToCharIndex("明月", 99))
        assertEquals(0, SearchText.charIndexToCodePoint("明月", -1))
        assertEquals(2, SearchText.charIndexToCodePoint("明月", 99))
    }

    @Test
    fun `高亮区间返回码点而非 char 索引`() {
        val ranges = SearchText.highlightsOf(rareText, "月")
        assertEquals(1, ranges.size)
        // 若误用 char 索引会得到 2，这正是生僻字高亮偏移的成因
        assertEquals(1, ranges.first().start)
        assertEquals(2, ranges.first().end)
    }

    @Test
    fun `同一段文本里多次命中全部返回`() {
        // 举(0)头(1)望(2)明(3)月(4)，(5)低(6)头(7)思(8)故(9)乡(10)。(11)明(12)月(13)
        val ranges = SearchText.highlightsOf("举头望明月，低头思故乡。明月", "明月")
        assertEquals(2, ranges.size)
        assertEquals(3, ranges[0].start)
        assertEquals(5, ranges[0].end)
        assertEquals(12, ranges[1].start)
        assertEquals(14, ranges[1].end)
    }

    @Test
    fun `大小写不敏感`() {
        val ranges = SearchText.highlightsOf("LiBai 与 libai", "LIBai")
        assertEquals(2, ranges.size)
        assertTrue(ranges.all { it.end - it.start == 5 })
    }

    @Test
    fun `关键词含正则元字符不会崩也不会误匹配`() {
        // 走 indexOf 而不是正则，所以 ( 与 * 是普通字符
        assertEquals(1, SearchText.highlightsOf("a(b", "(").size)
        assertEquals(0, SearchText.highlightsOf("ab", "a(b").size)
    }

    @Test
    fun `空输入返回空区间`() {
        assertTrue(SearchText.highlightsOf("", "月").isEmpty())
        assertTrue(SearchText.highlightsOf("明月", "").isEmpty())
    }

    @Test
    fun `标题或作者命中判定`() {
        assertTrue(SearchText.matchesTitleOrAuthor("静夜思", "李白", "静夜"))
        assertTrue(SearchText.matchesTitleOrAuthor("静夜思", "李白", "李白"))
        assertTrue(SearchText.matchesTitleOrAuthor("静夜思", "李白", "白"))
        assertTrue(!SearchText.matchesTitleOrAuthor("静夜思", "李白", "明月"))
    }
}

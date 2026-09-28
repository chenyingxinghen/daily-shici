package com.example.daily_shici.domain.daily

import com.example.daily_shici.data.repository.deriveCharCount
import com.example.daily_shici.data.repository.deriveExcerpt
import com.example.daily_shici.data.repository.deriveLineCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyFallbackTest {

    @Test
    fun `同一天多次调用得到同一个偏移`() {
        val date = LocalDate.of(2026, 9, 22)
        val first = DailyFallback.offsetFor(date, total = 70)
        repeat(20) {
            assertEquals(first, DailyFallback.offsetFor(date, total = 70))
        }
    }

    @Test
    fun `偏移始终落在有效范围内`() {
        val total = 70
        var date = LocalDate.of(2026, 1, 1)
        repeat(400) {
            val offset = DailyFallback.offsetFor(date, total)
            assertTrue("offset=$offset 越界", offset in 0 until total)
            date = date.plusDays(1)
        }
    }

    /**
     * 防回归：这条用例守住「不要用 `ORDER BY poemId * A + daySeed`」那个坑 ——
     * 那种写法下不同日期会落到同一个位置，等于天天同一首诗。
     */
    @Test
    fun `相邻日期的偏移不恒定`() {
        val total = 70
        val offsets = (0 until 30).map { day ->
            DailyFallback.offsetFor(LocalDate.of(2026, 9, 1).plusDays(day.toLong()), total)
        }
        val distinct = offsets.toSet()
        // 30 天里至少要有 15 个不同位置，否则等于「几乎天天同一首」
        assertTrue("30 天只出现了 ${distinct.size} 个不同位置", distinct.size >= 15)
        assertNotEquals(offsets[0], offsets[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `本地没有诗词时调用兜底应当失败而不是除零`() {
        DailyFallback.offsetFor(LocalDate.of(2026, 9, 22), total = 0)
    }

    @Test
    fun `预取只补缺口`() {
        val today = LocalDate.of(2026, 9, 22)

        // 缓存为空 → 补满 30 天窗口
        val fromEmpty = DailyFallback.prefetchCount(today, lastCached = null)
        assertEquals(DailyFallback.PREFETCH_DAYS.toInt(), fromEmpty)

        // 已缓存到未来 → 不用补
        assertEquals(0, DailyFallback.prefetchCount(today, today.plusDays(30)))

        // 已缓存到 10 天后 → 只补剩下的 20 天
        assertEquals(20, DailyFallback.prefetchCount(today, today.plusDays(10)))

        // 缓存落后于今天（断网很久）→ 从今天重新开始补
        assertEquals(
            DailyFallback.PREFETCH_DAYS.toInt(),
            DailyFallback.prefetchCount(today, today.minusDays(5)),
        )
    }

    @Test
    fun `预取起点取今天与缓存最大日期的较大者`() {
        val today = LocalDate.of(2026, 9, 22)
        assertEquals(today.plusDays(1), DailyFallback.prefetchStart(today, today))
        assertEquals(today, DailyFallback.prefetchStart(today, today.minusDays(3)))
        assertEquals(today, DailyFallback.prefetchStart(today, null))
    }
}

/** 入库时的派生字段：列表摘要取首个非空行，行数/字数按真实换行统计。 */
class DerivedFieldTest {

    @Test
    fun `摘要取首个非空行`() {
        assertEquals("床前明月光，疑是地上霜。", deriveExcerpt("床前明月光，疑是地上霜。\n举头望明月，低头思故乡。"))
        assertEquals("静夜思", deriveExcerpt("\n\n静夜思\n正文"))
        assertEquals("", deriveExcerpt(""))
        assertEquals("", deriveExcerpt("\n\n"))
    }

    @Test
    fun `行数按换行计`() {
        assertEquals(1, deriveLineCount("单行"))
        assertEquals(2, deriveLineCount("一\n二"))
        assertEquals(3, deriveLineCount("一\n二\n三"))
        // 尾随换行会产生一个空行，这是包格式的既定行为，不额外修剪
        assertEquals(3, deriveLineCount("一\n二\n"))
    }

    @Test
    fun `字数不含空白`() {
        // 床(1)前(2)明(3)月(4)光(5)，(6)疑(7)是(8)地(9)上(10)霜(11)。(12)
        assertEquals(12, deriveCharCount("床前明月光，疑是地上霜。"))
        assertEquals(12, deriveCharCount("床前明月光， 疑是地上霜。"))
        assertEquals(12, deriveCharCount("床前明月光，\n疑是地上霜。"))
    }
}

package com.example.daily_shici.domain.daily

import java.time.LocalDate

/**
 * 离线兜底选诗：缓存未命中（断网超过缓存水位）时，从**本地已下载的全部诗词**里挑一首。
 *
 * 关键性质是**确定性**：同一天多次打开必须得到同一首。
 * 否则用户会感觉「这 App 在乱跳」。所以是按日期算偏移，而不是真随机。
 */
object DailyFallback {

    /** Knuth 乘法散列常量。 */
    private const val KNUTH = 2654435761L

    /**
     * 日期 → 行偏移量。
     *
     * 用取模把日期打散，而不是 `ORDER BY (poemId * A + daySeed) LIMIT 1`——
     * 后者是 `poemId` 的单调递增函数，排序结果与 `ORDER BY poemId` 完全等价，
     * 日期种子不产生任何影响，每天都会返回同一个最小 poemId。
     * 这写法看起来像随机、实际毫无随机性，是本项目明确记过的一处坑。
     *
     * @param total 本地诗词总数，必须 > 0
     */
    fun offsetFor(date: LocalDate, total: Int): Int {
        require(total > 0) { "本地没有诗词时不应调用兜底选诗" }
        val seed = date.toEpochDay() * KNUTH
        return ((seed % total) + total).toInt() % total
    }

    /** 缓存水位：只保留最近 365 天的每日一诗，防止无限增长。 */
    const val CACHE_DAYS = 365L

    /** 预取窗口：一次补齐未来 30 天。 */
    const val PREFETCH_DAYS = 30L

    /** 预取起点：缓存里最大日期的下一天。 */
    fun prefetchStart(today: LocalDate, lastCached: LocalDate?): LocalDate =
        maxOf(today, lastCached?.plusDays(1) ?: today)

    /** 需要预取的天数；已补齐时返回 0。 */
    fun prefetchCount(today: LocalDate, lastCached: LocalDate?): Int {
        val from = prefetchStart(today, lastCached)
        val until = today.plusDays(PREFETCH_DAYS)
        val days = java.time.temporal.ChronoUnit.DAYS.between(from, until).toInt() + 1
        return days.coerceIn(0, PREFETCH_DAYS.toInt())
    }
}

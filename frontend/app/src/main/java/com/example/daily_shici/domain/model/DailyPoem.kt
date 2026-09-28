package com.example.daily_shici.domain.model

/** 每日一诗。 [isOfflineFallback] 为真时 UI 需标注「离线精选」。 */
data class DailyPoem(
    val date: java.time.LocalDate,
    val poem: PoemDetail,
    /** 三级读取里第 3 级（缓存未命中、本地兜底选诗）置为 true。 */
    val isOfflineFallback: Boolean = false,
) {
    /** 「乙巳年 九月廿二 · 星期二」——设计稿的那行小字。 */
    val lunarLine: String get() = ChineseDate.formatLunarTitle(date)
}

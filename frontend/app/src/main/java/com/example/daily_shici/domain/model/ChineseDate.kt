package com.example.daily_shici.domain.model

import android.icu.util.ChineseCalendar
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 农历日期 → 「乙巳年 九月廿二 · 星期二」。
 *
 * 用系统自带的 [ChineseCalendar]（android.icu，API 24+）而不是塞一张农历查表 ——
 * 表要覆盖 1900–2100 约 200 个 32 位整数，抄错一位就是一整年日期错位，
 * 而 ICU 的实现是随系统更新的。
 *
 * 注意：本对象依赖 android.icu，**只能在仪器测试里跑**，JVM 单测拿不到该类。
 */
object ChineseDate {

    private val STEMS = listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    private val BRANCHES = listOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
    private val MONTHS = listOf(
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月"
    )
    private val DAYS = listOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    )
    private val WEEKDAYS = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    /** 干支纪年，如 2025 → 乙巳、2026 → 丙午。 */
    fun ganzhiYear(lunarYear: Int): String {
        val cycle = ((lunarYear - 4) % 60 + 60) % 60
        return STEMS[cycle % 10] + BRANCHES[cycle % 12]
    }

    fun lunarMonthName(monthIndex: Int, isLeapMonth: Boolean): String {
        val base = MONTHS[monthIndex.coerceIn(0, 11)]
        return if (isLeapMonth) "闰$base" else base
    }

    fun lunarDayName(day: Int): String = DAYS[day.coerceIn(1, 30) - 1]

    fun weekdayName(date: LocalDate): String = WEEKDAYS[date.dayOfWeek.value - 1]

    /**
     * 完整一行：`乙巳年 九月廿二 · 星期二`
     */
    fun formatLunarTitle(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val calendar = ChineseCalendar().apply {
            timeInMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        }
        val lunarYear = calendar.get(ChineseCalendar.EXTENDED_YEAR)
        val month = calendar.get(ChineseCalendar.MONTH)
        val day = calendar.get(ChineseCalendar.DAY_OF_MONTH)
        val isLeap = calendar.get(ChineseCalendar.IS_LEAP_MONTH) == 1

        val head = ganzhiYear(lunarYear) + "年"
        val tail = lunarMonthName(month, isLeap) + lunarDayName(day)
        return "$head $tail · ${weekdayName(date)}"
    }

    /** 缓存水位与包下载偏移量都用毫秒时间戳，这里给个统一入口避免各处手写换算。 */
    fun epochMillis(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** 把「已下载的毫秒数」格式化成人类可读的 `1.2 MB`。 */
    fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    }

    /** 把「剩余时长」格式化成 `约 2 分 10 秒`。 */
    fun humanDuration(millis: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "约 $minutes 分 $seconds 秒" else "约 $seconds 秒"
    }
}

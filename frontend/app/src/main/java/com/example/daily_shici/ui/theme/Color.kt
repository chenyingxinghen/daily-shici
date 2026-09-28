package com.example.daily_shici.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 素纸朱栏 · 设计令牌取自设计稿的 fill 浮点值（0~1），此处换算为 8 位十六进制。
 *
 * | 令牌 | 设计稿 RGB | hex |
 * |---|---|---|
 * | 纸底 | 0.980 / 0.961 / 0.922 | #FAF5EB |
 * | 墨 | 0.102 / 0.102 / 0.102 | #1A1A1A |
 * | 朱红 | 0.773 / 0.227 / 0.165 | #C53A2A |
 * | 次级文字 | 0.361 / 0.329 / 0.282 | #5C5449 |
 * | 三级文字 | 0.604 / 0.561 / 0.486 | #9A8F7C |
 * | 朱丝栏细线 | 0.898 / 0.867 / 0.784 | #E5DDC8 |
 * | 加重细线 | 0.847 / 0.812 / 0.733 | #D8CFBB |
 */
object ShiciColors {

    /** 纸底。整个 App 只有这一个背景色。 */
    val Paper = Color(0xFFFAF5EB)

    /** 墨色 —— 正文与标题。 */
    val Ink = Color(0xFF1A1A1A)

    /** 朱红 —— 印章、当前选项、命中高亮、强调链接。全局唯一的彩色。 */
    val Vermilion = Color(0xFFC53A2A)

    /** 次级文字：摘要、作者、菜单项。 */
    val InkSoft = Color(0xFF5C5449)

    /** 三级文字：元信息、计数、占位符。 */
    val InkFaint = Color(0xFF9A8F7C)

    /** 朱丝栏细线：列表分隔线、区块横线。 */
    val Rule = Color(0xFFE5DDC8)

    /** 加重细线：输入域下划线、非强调链接的下划线。 */
    val RuleStrong = Color(0xFFD8CFBB)
}

package com.example.daily_shici.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * 字体族。
 *
 * 设计稿用的是 Noto Serif SC / Noto Sans SC。Android 系统自带 Noto Serif CJK 与
 * Noto Sans CJK，[FontFamily.Serif] / [FontFamily.SansSerif] 会解析到它们，
 * 因此无需把 woff2 打包进 APK（设计稿给的是 web 字体格式，Android 不直接吃）。
 *
 * 诗词正文一律走 [SerifSC]（衬线），界面文字走 [SansSC]（无衬线）——
 * 这条分工贯穿全部页面，是设计语言的一部分。
 */
val SerifSC = FontFamily.Serif
val SansSC = FontFamily.SansSerif

private val tightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

/** 设计稿里出现的全部文字样式，命名对齐设计稿的图层名。 */
object ShiciText {

    /** 农历日期 · 12sp Sans Medium · 朱红 */
    val Date = TextStyle(
        fontFamily = SansSC, fontWeight = FontWeight.Medium, fontSize = 12.sp
    )

    /** 每日一诗标题 · 30sp Serif Bold */
    val DailyTitle = TextStyle(
        fontFamily = SerifSC, fontWeight = FontWeight.Bold, fontSize = 30.sp
    )

    /** 正文（每日一诗）· 20sp Serif Regular · 行高 44sp */
    val DailyBody = TextStyle(
        fontFamily = SerifSC, fontWeight = FontWeight.Normal,
        fontSize = 20.sp, lineHeight = 44.sp, lineHeightStyle = tightLineHeight
    )

    /** 正文（详情页）· 17sp Serif Regular · 行高 34sp */
    val DetailBody = TextStyle(
        fontFamily = SerifSC, fontWeight = FontWeight.Normal,
        fontSize = 17.sp, lineHeight = 34.sp, lineHeightStyle = tightLineHeight
    )

    /** 印章文字 · 20sp Serif Bold */
    val SealText = TextStyle(
        fontFamily = SerifSC, fontWeight = FontWeight.Bold, fontSize = 20.sp
    )

    /** 作者 · 13sp Sans Regular */
    val Author = TextStyle(
        fontFamily = SansSC, fontWeight = FontWeight.Normal, fontSize = 13.sp
    )

    /**
     * 注释 / 译文 / 赏析正文 · 15sp Sans Regular · 行高 26sp。
     *
     * **为什么是 Sans 而不是 Serif**：这条分工延续了「诗词正文走衬线、界面文字走无衬线」
     * （见文件头）。注疏是**编辑加工的文字**，不是作品本身 —— 用无衬线把它和上面的
     * 诗身在视觉上分层，用户一眼能看出哪是原文、哪是解释。
     *
     * 行高 26sp（约 1.7 倍）比 [DetailBody] 的 34/17（2 倍）紧，比 [MenuLabel] 的默认值松：
     * 注疏是连续散文，太紧读着累，太松会把整页撑得很长。
     */
    val AnnotationBody = TextStyle(
        fontFamily = SansSC, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 26.sp, lineHeightStyle = tightLineHeight
    )

    /** 页面大标题（浏览 / 我的）· 28sp Serif Bold */
    val ScreenTitle = TextStyle(
        fontFamily = SerifSC, fontWeight = FontWeight.Bold, fontSize = 28.sp
    )

    /** 详情页诗词标题 · 26sp Serif Bold */
    val DetailTitle = TextStyle(
        fontFamily = SerifSC, fontWeight = FontWeight.Bold, fontSize = 26.sp
    )

    /** 列表条目标题 · 17sp Serif SemiBold */
    val EntryTitle = TextStyle(
        fontFamily = SerifSC, fontWeight = FontWeight.SemiBold, fontSize = 17.sp
    )

    /** 列表条目摘要 · 13sp Sans Regular */
    val EntryExcerpt = TextStyle(
        fontFamily = SansSC, fontWeight = FontWeight.Normal, fontSize = 13.sp
    )

    /** 列表条目元信息 · 12sp Sans Regular */
    val EntryMeta = TextStyle(
        fontFamily = SansSC, fontWeight = FontWeight.Normal, fontSize = 12.sp
    )

    /** 筛选项 · 14sp Sans Regular */
    val Filter = TextStyle(
        fontFamily = SansSC, fontWeight = FontWeight.Normal, fontSize = 14.sp
    )

    /** 选中的筛选项 · 14sp Sans Medium */
    val FilterActive = TextStyle(
        fontFamily = SansSC, fontWeight = FontWeight.Medium, fontSize = 14.sp
    )

    /** 朝代项 · 13sp Sans Regular */
    val Dynasty = TextStyle(
        fontFamily = SansSC, fontWeight = FontWeight.Normal, fontSize = 13.sp
    )

    /** 朝代项（选中）· 13sp Sans Medium */
    val DynastyActive = TextStyle(
        fontFamily = SansSC, fontWeight = FontWeight.Medium, fontSize = 13.sp
    )

    /** 下划线链接（读全文 / 收藏 / 复制 / 分享）· 14sp Sans Medium */
    val Link = TextStyle(
        fontFamily = SansSC, fontWeight = FontWeight.Medium, fontSize = 14.sp
    )

    /** 无下划线链接（复制 / 分享）· 14sp Sans Regular */
    val LinkPlain = TextStyle(
        fontFamily = SansSC, fontWeight = FontWeight.Normal, fontSize = 14.sp
    )

    /** 底部导航标签 · 10sp */
    val NavLabel = TextStyle(
        fontFamily = SansSC, fontWeight = FontWeight.Normal, fontSize = 10.sp
    )

    /** 底部导航标签（选中）· 10sp Sans Medium */
    val NavLabelActive = TextStyle(
        fontFamily = SansSC, fontWeight = FontWeight.Medium, fontSize = 10.sp
    )

    /** 区块小标题（热门搜索 / 搜索历史）· 12sp Sans Medium */
    val SectionLabel = TextStyle(
        fontFamily = SansSC, fontWeight = FontWeight.Medium, fontSize = 12.sp
    )

    /** 正文段落标题（搜索结果）· 16sp Serif SemiBold */
    val SectionTitle = TextStyle(
        fontFamily = SerifSC, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
    )

    /** 搜索占位符 · 15sp Sans Regular */
    val Placeholder = TextStyle(
        fontFamily = SansSC, fontWeight = FontWeight.Normal, fontSize = 15.sp
    )

    /** 菜单项 · 15sp Sans Regular */
    val MenuLabel = TextStyle(
        fontFamily = SansSC, fontWeight = FontWeight.Normal, fontSize = 15.sp
    )

    /** 个人页昵称 · 20sp Serif SemiBold */
    val ProfileName = TextStyle(
        fontFamily = SerifSC, fontWeight = FontWeight.SemiBold, fontSize = 20.sp
    )
}

/**
 * 只把被 Material 组件直接消费的几项接进 [Typography]；
 * 业务代码请直接使用 [ShiciText]，避免中间层二次改名造成对不上设计稿。
 */
val ShiciTypography = Typography(
    bodyLarge = ShiciText.MenuLabel,
    titleLarge = ShiciText.ScreenTitle,
    labelSmall = ShiciText.SectionLabel
)

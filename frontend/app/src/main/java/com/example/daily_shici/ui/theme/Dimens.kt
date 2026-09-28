package com.example.daily_shici.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 尺寸令牌。数值逐条对应设计稿里的 padding / gap / 宽高，不另行取整。
 */
object ShiciDimens {

    /** 页面内容左右内边距（浏览 / 详情 / 搜索 / 我的 / 每日一诗的 Content 区）。 */
    val ContentPadding = 28.dp

    /** 底部导航：左右 30 / 上 12 / 下 24，总高 76。 */
    val NavPadding = 30.dp
    val NavTop = 12.dp
    val NavBottom = 24.dp
    val NavHeight = 76.dp
    val NavIconSize = 20.dp
    val NavItemGap = 5.dp
    val NavMarkWidth = 18.dp
    val NavMarkHeight = 2.dp

    /** 朱丝栏细线。 */
    val Hairline = 1.dp
    val HairlineRadius = 1.dp

    /** 强调下划线（读全文 / 收藏）。 */
    val UnderlineHeight = 1.5.dp

    /** 印章。 */
    val SealSize = 38.dp
    val SealRadius = 4.dp

    /** 图标（操作区 / 返回 / 菜单行）。 */
    val IconSmall = 18.dp
    val IconMedium = 20.dp
    val IconLarge = 24.dp
    val IconXLarge = 22.dp

    /** 每日一诗操作区：链接与心形图标之间的间距。 */
    val DailyActionsGap = 28.dp

    /** 列表条目内部字号层级之间的间距。 */
    val EntryGap = 5.dp

    /** 筛选行 / 朝代行 / 热门行 / 推荐行的横向间距。 */
    val FilterRowGap = 20.dp
    val InlineRowGap = 16.dp
    val InlineRowGapLoose = 18.dp

    /** 详情页操作链接区横向间距。 */
    val ActionLinksGap = 26.dp

    /** 选中标记与文字之间的间距（筛选 / 朝代 / 热门）。 */
    val ActiveMarkGap = 4.dp

    /** 表情/筛选项下方标记的间距（朝代用小一点的值）。 */
    val ActiveMarkGapTight = 3.dp

    /** 菜单行左右图标与文字间距、上下内边距。 */
    val MenuRowGap = 14.dp
    val MenuRowPaddingVertical = 12.dp

    /** 页面内容区上下内边距：浏览 / 搜索 / 我的 用 22，每日一诗用 24，详情用 20。 */
    val ContentPaddingVertical = 22.dp
    val DailyContentPaddingVertical = 24.dp
    val DetailContentPaddingVertical = 20.dp

    /** 内容区纵向间距：每日一诗 / 详情 / 搜索 = 20，浏览 = 18，我的 = 16。 */
    val ContentGap = 20.dp
    val BrowseContentGap = 18.dp
    val ProfileContentGap = 16.dp
}

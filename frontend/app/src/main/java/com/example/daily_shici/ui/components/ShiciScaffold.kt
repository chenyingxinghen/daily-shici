package com.example.daily_shici.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.daily_shici.ui.theme.ShiciColors
import com.example.daily_shici.ui.theme.ShiciDimens
import com.example.daily_shici.ui.theme.ShiciText

/** 底栏的四个 Tab，顺序与设计稿一致。 */
enum class BottomTab(val label: String) {
    DAILY("每日"),
    BROWSE("浏览"),
    SEARCH("搜索"),
    MINE("我的"),
}

/**
 * 系统状态栏留白。
 *
 * 这里**不再自绘状态栏**（原先画 9:41 与假信号/电池图标）—— 真机上系统状态栏本来就在，
 * 再画一条等于显示两遍时间。
 *
 * 但留白仍然必须有：`enableEdgeToEdge()` 之后内容会铺到状态栏下面，
 * 不显式让出 insets 的话正文会顶到时间底下。让出的高度由系统 insets 决定，
 * 不再写死 62dp（刘海屏、无刘海屏、平板各不相同）。
 */
@Composable
fun SystemStatusBarInset(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
    )
}

/**
 * 底部导航。
 *
 * 选中态由三处共同表达：图标与文字变朱红、文字由 Regular 变 Medium、
 * 下方出现 18x2 的朱红短标记。只有颜色变化的话在细线风格里太弱。
 */
@Composable
fun ShiciBottomNav(
    selected: BottomTab,
    onSelect: (BottomTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ShiciColors.Paper)
            // 让开系统导航栏（手势条/虚拟键），否则标签会被压在下面点不到
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(ShiciDimens.NavHeight)
            .padding(
                start = ShiciDimens.NavPadding,
                end = ShiciDimens.NavPadding,
                top = ShiciDimens.NavTop,
                bottom = ShiciDimens.NavBottom,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomTab.entries.forEach { tab ->
            val active = tab == selected
            val color = if (active) ShiciColors.Vermilion else ShiciColors.InkSoft

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ShiciDimens.NavItemGap),
            ) {
                when (tab) {
                    BottomTab.DAILY -> HomeIcon(size = ShiciDimens.NavIconSize, color = color)
                    BottomTab.BROWSE -> BrowseIcon(size = ShiciDimens.NavIconSize, color = color)
                    BottomTab.SEARCH -> SearchIcon(size = ShiciDimens.NavIconSize, color = color)
                    BottomTab.MINE -> PersonIcon(size = ShiciDimens.NavIconSize, color = color)
                }
                Text(
                    text = tab.label,
                    style = if (active) ShiciText.NavLabelActive else ShiciText.NavLabel,
                    color = color,
                )
                // 未选中时不画标记，但仍占位，避免切换时整行跳动
                Box(
                    modifier = Modifier
                        .width(ShiciDimens.NavMarkWidth)
                        .height(ShiciDimens.NavMarkHeight)
                        .background(
                            if (active) ShiciColors.Vermilion else Color.Transparent,
                            RoundedCornerShape(ShiciDimens.HairlineRadius),
                        )
                )
            }
        }
    }
}

/**
 * 带底部导航的页面外壳。
 *
 * 顺序：系统状态栏留白 → 内容（可滚动）→ 底栏。
 * 内容区**左右内边距统一 28dp**，与设计稿一致。
 */
@Composable
fun ShiciTabScreen(
    selectedTab: BottomTab,
    onSelectTab: (BottomTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ShiciColors.Paper),
    ) {
        SystemStatusBarInset()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(
                    start = ShiciDimens.ContentPadding,
                    end = ShiciDimens.ContentPadding,
                    top = ShiciDimens.ContentPaddingVertical,
                    bottom = ShiciDimens.ContentPaddingVertical,
                )
                .heightIn(min = 0.dp),
            content = content,
        )
        ShiciBottomNav(selected = selectedTab, onSelect = onSelectTab)
    }
}

/** 空态/提示文案。设计稿没有空态稿，这里保持同一套语气与灰度。 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = ShiciText.EntryExcerpt, color = ShiciColors.InkFaint)
    }
}

/** 详情页顶栏：左返回、右操作区。design 稿的 Top Bar 是 space-between。 */
@Composable
fun DetailTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BackIcon(
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onBack() },
            size = ShiciDimens.IconLarge,
            color = ShiciColors.Ink,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ShiciDimens.InlineRowGapLoose)) {
            actions()
        }
    }
}

/** 竖向留白，供各页面在无 Scroll 时把内容推到设计稿的位置。 */
@Composable
fun VerticalGap(height: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(height))

/**
 * 「内容短时垂直居中、内容长时可滚动」的容器。
 *
 * 每日一诗与详情页的设计都是**整体垂直居中**的立轴式排版（内容块的几何中心
 * 落在内容区中点）。但直接给 Column 加 `Arrangement.Center` 在最外层有
 * `verticalScroll` 时会失效 —— 滚动容器的内容高度等于子项高度，没有可分配的多余空间。
 *
 * 所以这里用 [BoxWithConstraints] 拿到视口高度，把子内容的最小高度顶到视口高，
 * 居中才有意义；一旦内容超过视口，就自然变成可滚动。
 */
@Composable
fun CenteredScrollColumn(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    verticalGap: androidx.compose.ui.unit.Dp,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = viewportHeight)
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(verticalGap, Alignment.CenterVertically),
                horizontalAlignment = horizontalAlignment,
                content = content,
            )
        }
    }
}

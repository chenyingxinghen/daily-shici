package com.example.daily_shici.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.daily_shici.ui.theme.ShiciColors
import com.example.daily_shici.ui.theme.ShiciDimens
import com.example.daily_shici.ui.theme.ShiciText

/**
 * 朱丝栏细线。设计稿里所有分隔都是「1dp 实线 + 纸色底」，
 * 没有圆角卡片、没有阴影、没有间距留白块 —— 这是「无卡片」设计语言的核心。
 */
@Composable
fun HairlineRule(
    modifier: Modifier = Modifier,
    color: Color = ShiciColors.Rule,
    thickness: Dp = ShiciDimens.Hairline,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(color, RoundedCornerShape(ShiciDimens.HairlineRadius))
    )
}

/**
 * 朱红印章。设计稿里它出现在每日一诗正文之后，是全屏唯一的实心色块。
 */
@Composable
fun Seal(
    text: String = "詩",
    modifier: Modifier = Modifier,
    size: Dp = ShiciDimens.SealSize,
    style: TextStyle = ShiciText.SealText,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(ShiciDimens.SealRadius))
            .background(ShiciColors.Vermilion),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = style, color = ShiciColors.Paper)
    }
}

/**
 * 带下划线标记的文字，用于筛选行 / 朝代行 / 链接。
 *
 * 下划线宽度等于文字宽度，所以在 `IntrinsicSize.Max` 的 Column 里让子项 fillMaxWidth，
 * 否则会出现「线比字长」的观感（设计稿的线是贴着字的）。
 *
 * @param underlined 下划线是否显示。设计稿里**未选中项完全不画线**，
 *   只有选中项和强调链接有线 —— 所以不能一律画浅色线。
 */
@Composable
fun MarkedText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ShiciColors.InkSoft,
    style: TextStyle = ShiciText.Filter,
    underlined: Boolean = false,
    underlineColor: Color = ShiciColors.Vermilion,
    underlineThickness: Dp = ShiciDimens.UnderlineHeight,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .width(IntrinsicSize.Max)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.spacedBy(
            if (underlined) ShiciDimens.ActiveMarkGapTight else 0.dp
        ),
    ) {
        Text(text = text, style = style, color = color)
        if (underlined) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(underlineThickness)
                    .background(underlineColor, RoundedCornerShape(ShiciDimens.HairlineRadius))
            )
        }
    }
}

/** 强调链接：朱红文字 + 朱红下划线（读全文 / 收藏）。 */
@Composable
fun AccentLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MarkedText(
        text = text,
        modifier = modifier,
        color = ShiciColors.Vermilion,
        style = ShiciText.Link,
        underlined = true,
        underlineColor = ShiciColors.Vermilion,
        underlineThickness = ShiciDimens.UnderlineHeight,
        onClick = onClick,
    )
}

/** 次级链接：墨色文字 + 浅色下划线（复制 / 分享）。 */
@Composable
fun QuietLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MarkedText(
        text = text,
        modifier = modifier,
        color = ShiciColors.Ink,
        style = ShiciText.LinkPlain,
        underlined = true,
        underlineColor = ShiciColors.RuleStrong,
        underlineThickness = ShiciDimens.Hairline,
        onClick = onClick,
    )
}

/** 区块小标题（热门搜索 / 搜索历史），统一次级灰。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = ShiciText.SectionLabel,
        color = ShiciColors.InkFaint,
    )
}

/** 页面大标题。 */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier, style = ShiciText.ScreenTitle, color = ShiciColors.Ink)
}

/** 列表条目下方的元信息行（唐 · 李白）。 */
@Composable
fun EntryMeta(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier, style = ShiciText.EntryMeta, color = ShiciColors.InkFaint)
}

/** 带下边距的细线，用于列表项之间的分隔。 */
@Composable
fun ListDivider(modifier: Modifier = Modifier) {
    HairlineRule(modifier = modifier.padding(vertical = 0.dp))
}

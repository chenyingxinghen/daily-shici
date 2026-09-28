package com.example.daily_shici.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.example.daily_shici.domain.model.HighlightRange
import com.example.daily_shici.domain.search.SearchText
import com.example.daily_shici.ui.theme.ShiciColors

/**
 * 把契约给出的高亮区间渲染成朱红加粗的片段。
 *
 * 关键点：契约的 `highlights` 索引单位是 **Unicode 码点**，而 Compose 的
 * `AnnotatedString` 用的是 UTF-16 char 索引。两者对 BMP 内字符一致，
 * 但只要出现一个生僻字（如作品名里的 𠀋）就整体错位。
 * 因此必须经 [SearchText.codePointToCharIndex] 换算 —— 这一步少写，
 * 平时全对、只有生僻字的高亮位置偏移，是极难人工发现的 bug。
 */
@Composable
fun HighlightedText(
    text: String,
    ranges: List<HighlightRange>,
    style: TextStyle,
    color: Color = ShiciColors.InkSoft,
    highlightColor: Color = ShiciColors.Vermilion,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val annotated = rememberHighlighted(text, ranges, highlightColor)
    Text(
        text = annotated,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

@Composable
private fun rememberHighlighted(
    text: String,
    ranges: List<HighlightRange>,
    highlightColor: Color,
): AnnotatedString = androidx.compose.runtime.remember(text, ranges, highlightColor) {
    if (ranges.isEmpty()) return@remember AnnotatedString(text)

    val charRanges = ranges.mapNotNull { range ->
        val start = SearchText.codePointToCharIndex(text, range.start)
        val end = SearchText.codePointToCharIndex(text, range.end)
        if (start < end && start < text.length) start.coerceIn(0, text.length) to end.coerceIn(0, text.length)
        else null
    }.sortedBy { it.first }

    buildAnnotatedString {
        var cursor = 0
        for ((start, end) in charRanges) {
            if (start < cursor) continue // 重叠区间跳过，避免 withStyle 嵌套导致样式叠加
            if (start > cursor) append(text.substring(cursor, start))
            withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Medium)) {
                append(text.substring(start, end))
            }
            cursor = end
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

package com.example.daily_shici.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 设计稿只有一套「素纸」配色，没有深色模式（属未设计项）。
 * 因此这里刻意 **不** 接入 dynamicColor —— 动态取色会把纸底换成壁纸色，
 * 直接破坏设计语言。
 */
private val ShiciColorScheme = lightColorScheme(
    primary = ShiciColors.Vermilion,
    onPrimary = ShiciColors.Paper,
    secondary = ShiciColors.InkSoft,
    onSecondary = ShiciColors.Paper,
    tertiary = ShiciColors.InkFaint,
    background = ShiciColors.Paper,
    onBackground = ShiciColors.Ink,
    surface = ShiciColors.Paper,
    onSurface = ShiciColors.Ink,
    surfaceVariant = ShiciColors.Paper,
    onSurfaceVariant = ShiciColors.InkSoft,
    outline = ShiciColors.Rule,
    outlineVariant = ShiciColors.RuleStrong,
    error = ShiciColors.Vermilion,
    scrim = ShiciColors.Ink,
)

@Composable
fun DailyshiciTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ShiciColorScheme,
        typography = ShiciTypography,
        content = content
    )
}

/** 纸底容器。每个页面的根节点都用它，避免背景色漏配。 */
@Composable
fun PaperSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ShiciColors.Paper)
    ) {
        content()
    }
}

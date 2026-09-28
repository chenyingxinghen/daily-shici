package com.example.daily_shici.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计稿的图标全部是**细线描边**（20 单位画布上 1.25 / 1.333 的 stroke），
 * 没有一个是实心 Material 图标。用 Material Icons 替代会一眼看出粗细与收笔不对，
 * 所以这里按设计稿给出的几何坐标逐个手绘。
 *
 * 约定：所有图形在 [unit] 单位的坐标系里描述，再整体缩放到目标尺寸。
 * 因此描边宽度也是「单位值」——放大图标时线宽同比放大，与设计稿的等比行为一致。
 */
private val ICON_UNIT = 20f

@Composable
private fun VectorIcon(
    modifier: Modifier,
    size: Dp,
    color: Color,
    unit: Float = ICON_UNIT,
    draw: DrawScope.() -> Unit,
) {
    Canvas(modifier = modifier.size(size)) {
        val factor = this.size.minDimension / unit
        withTransform({ scale(factor, factor, pivot = Offset.Zero) }) {
            draw()
        }
    }
}

/** 统一的描边样式。圆头圆角收笔，对应设计稿的细线观感。 */
private fun stroke(width: Float) = Stroke(
    width = width,
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
)

// ---------------------------------------------------------------- 底部导航

/** 每日 · 屋檐。设计稿几何：15 x 13.33，位于 (2.5, 3.33)。 */
@Composable
fun HomeIcon(modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color) {
    VectorIcon(modifier, size, color) {
        val s = stroke(1.3333f)
        val roof = Path().apply {
            moveTo(2.5f, 8.33f)
            lineTo(10f, 3.33f)
            lineTo(17.5f, 8.33f)
        }
        drawPath(roof, color, style = s)
        val body = Path().apply {
            moveTo(3.75f, 8.33f)
            lineTo(3.75f, 16.67f)
            lineTo(16.25f, 16.67f)
            lineTo(16.25f, 8.33f)
        }
        drawPath(body, color, style = s)
        val door = Path().apply {
            moveTo(8.33f, 16.67f)
            lineTo(8.33f, 12.5f)
            lineTo(11.67f, 12.5f)
            lineTo(11.67f, 16.67f)
        }
        drawPath(door, color, style = s)
    }
}

/** 搜索 · 放大镜。设计稿几何：圆 11.67 位于 (3.33,3.33)，手柄在 (13.75,13.75)。 */
@Composable
fun SearchIcon(modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color) {
    VectorIcon(modifier, size, color) {
        val s = stroke(1.3333f)
        drawCircle(color, radius = 5.8333f, center = Offset(9.1667f, 9.1667f), style = s)
        drawLine(
            color,
            start = Offset(13.75f, 13.75f),
            end = Offset(16.6667f, 16.6667f),
            strokeWidth = 1.3333f,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 浏览 · 司南罗盘。
 *
 * ⚠️ 当前设计稿文件里，「浏览」与「搜索」用的是**完全相同的放大镜矢量**
 * （节点 4:248 与 4:254 的几何数据逐个值一致）。同一底栏放两个一模一样的图标
 * 会让用户无法区分，这是制图复用遗漏而非设计意图。
 *
 * 按本项目此前已确认的修改方向（见 .workbuddy/memory/2026-09-22.md：浏览改司南罗盘）
 * 实现为罗盘。若设计方决定保持字面一致，把本函数体换成 [SearchIcon] 即可。
 */
@Composable
fun BrowseIcon(modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color) {
    VectorIcon(modifier, size, color) {
        val s = stroke(1.3333f)
        drawCircle(color, radius = 6.6667f, center = Offset(10f, 10f), style = s)
        // 指针：北东与南西两个尖角构成的菱形，比圆形更有「指向」的语义
        val needle = Path().apply {
            moveTo(13.3333f, 6.6667f)
            lineTo(11.3333f, 11.3333f)
            lineTo(6.6667f, 13.3333f)
            lineTo(8.6667f, 8.6667f)
            close()
        }
        drawPath(needle, color, style = s)
    }
}

/** 我的 · 人像。头 5.83 位于 (7.08,3.75)，肩 11.67 x 5.83。 */
@Composable
fun PersonIcon(modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color) {
    VectorIcon(modifier, size, color) {
        val s = stroke(1.3333f)
        drawCircle(color, radius = 2.9167f, center = Offset(10f, 6.6667f), style = s)
        val shoulders = Path().apply {
            moveTo(4.1667f, 16.6667f)
            cubicTo(4.1667f, 13.4467f, 6.7783f, 10.8333f, 10f, 10.8333f)
            cubicTo(13.2217f, 10.8333f, 15.8333f, 13.4467f, 15.8333f, 16.6667f)
        }
        drawPath(shoulders, color, style = s)
    }
}

// ---------------------------------------------------------------- 操作区

/** 收藏 · 书签。10 x 13.33 位于 (5, 3.33)。 */
@Composable
fun BookmarkIcon(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color,
    filled: Boolean = false,
) {
    VectorIcon(modifier, size, color) {
        val ribbon = Path().apply {
            moveTo(5f, 3.3333f)
            lineTo(15f, 3.3333f)
            lineTo(15f, 16.6667f)
            lineTo(10f, 12.5f)
            lineTo(5f, 16.6667f)
            close()
        }
        if (filled) drawPath(ribbon, color) else drawPath(ribbon, color, style = stroke(1.25f))
    }
}

/** 心形 · 每日一诗页的收藏入口。16.16 x 15.5 位于 (1.92, 4.5) 于 24 画布。 */
@Composable
fun HeartIcon(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color,
    filled: Boolean = false,
) {
    Canvas(modifier = modifier.size(size)) {
        val factor = this.size.minDimension / 24f
        withTransform({ scale(factor, factor, pivot = Offset.Zero) }) {
            val heart = Path().apply {
                moveTo(10f, 19.6f)
                cubicTo(2.6f, 14.4f, 1.92f, 11.2f, 1.92f, 9.2f)
                cubicTo(1.92f, 6.4f, 4.1f, 4.5f, 6.5f, 4.5f)
                cubicTo(8.0f, 4.5f, 9.2f, 5.3f, 10f, 6.5f)
                cubicTo(10.8f, 5.3f, 12.0f, 4.5f, 13.5f, 4.5f)
                cubicTo(15.9f, 4.5f, 18.08f, 6.4f, 18.08f, 9.2f)
                cubicTo(18.08f, 11.2f, 17.4f, 14.4f, 10f, 19.6f)
                close()
            }
            if (filled) drawPath(heart, color) else drawPath(heart, color, style = stroke(1.5f))
        }
    }
}

/** 时钟 · 阅读历史。圆 13.33 + 时针分针。 */
@Composable
fun ClockIcon(modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color) {
    VectorIcon(modifier, size, color) {
        val s = stroke(1.25f)
        drawCircle(color, radius = 6.6667f, center = Offset(10f, 10f), style = s)
        drawLine(color, Offset(10f, 10f), Offset(10f, 5.8333f), strokeWidth = 1.25f, cap = StrokeCap.Round)
        drawLine(color, Offset(10f, 10f), Offset(12.9167f, 10f), strokeWidth = 1.25f, cap = StrokeCap.Round)
    }
}

/** 数据包 · 等距箱体。几何取自设计稿三个矢量的组合。 */
@Composable
fun PackageIcon(modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color) {
    VectorIcon(modifier, size, color) {
        val s = stroke(1.25f)
        val top = Path().apply {
            moveTo(2.5f, 6.6667f)
            lineTo(10f, 2.5f)
            lineTo(17.5f, 6.6667f)
            lineTo(10f, 10.8333f)
            close()
        }
        drawPath(top, color, style = s)
        drawLine(color, Offset(2.5f, 6.6667f), Offset(2.5f, 13.3333f), strokeWidth = 1.25f)
        drawLine(color, Offset(17.5f, 6.6667f), Offset(17.5f, 13.3333f), strokeWidth = 1.25f)
        val front = Path().apply {
            moveTo(2.5f, 13.3333f)
            lineTo(10f, 17.5f)
            lineTo(17.5f, 13.3333f)
        }
        drawPath(front, color, style = s)
        drawLine(color, Offset(10f, 10.8333f), Offset(10f, 17.5f), strokeWidth = 1.25f)
    }
}

/** 主题与排版 · 调色盘（外框 14.17 x 15 + 三个实心孔）。 */
@Composable
fun PaletteIcon(modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color) {
    VectorIcon(modifier, size, color) {
        val s = stroke(1.25f)
        val blob = Path().apply {
            moveTo(10f, 2.5f)
            cubicTo(5.6f, 2.5f, 2.5f, 5.9f, 2.5f, 9.9f)
            cubicTo(2.5f, 13.9f, 5.6f, 17.5f, 10f, 17.5f)
            cubicTo(11.1f, 17.5f, 11.6f, 16.9f, 11.6f, 16.0f)
            cubicTo(11.6f, 15.5f, 11.3f, 15.1f, 11.3f, 14.6f)
            cubicTo(11.3f, 13.7f, 11.9f, 13.1f, 12.8f, 13.1f)
            lineTo(14.8f, 13.1f)
            cubicTo(16.4f, 13.1f, 17.5f, 11.9f, 17.5f, 10.2f)
            cubicTo(17.5f, 5.9f, 14.2f, 2.5f, 10f, 2.5f)
            close()
        }
        drawPath(blob, color, style = s)
        listOf(Offset(6.6f, 8.3f), Offset(10f, 5.8f), Offset(13.3f, 8.3f)).forEach { center ->
            drawCircle(color, radius = 0.8333f, center = center)
        }
    }
}

/** 设置 · 齿轮。用内外圆 + 八根短齿表达，避免手写复杂贝塞尔出错。 */
@Composable
fun GearIcon(modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color) {
    VectorIcon(modifier, size, color) {
        drawCircle(color, radius = 2.5f, center = Offset(10f, 10f), style = stroke(1.25f))
        drawCircle(color, radius = 6.4f, center = Offset(10f, 10f), style = stroke(1.25f))
        repeat(8) { index ->
            val angle = Math.toRadians((index * 45).toDouble())
            val cos = kotlin.math.cos(angle).toFloat()
            val sin = kotlin.math.sin(angle).toFloat()
            drawLine(
                color,
                start = Offset(10f + 6.4f * cos, 10f + 6.4f * sin),
                end = Offset(10f + 8.3333f * cos, 10f + 8.3333f * sin),
                strokeWidth = 1.25f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 返回 · 左尖角。7 x 14 位于 (8, 5) 于 24 画布，描边 1.6。 */
@Composable
fun BackIcon(modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color) {
    Canvas(modifier = modifier.size(size)) {
        val factor = this.size.minDimension / 24f
        withTransform({ scale(factor, factor, pivot = Offset.Zero) }) {
            drawLine(
                color,
                start = Offset(15f, 5f),
                end = Offset(8f, 12f),
                strokeWidth = 1.6f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color,
                start = Offset(8f, 12f),
                end = Offset(15f, 19f),
                strokeWidth = 1.6f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 分享 · 三点连接图。三个 4.03 的圆位于 22 画布：左中、右上、右下。 */
@Composable
fun ShareIcon(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color) {
    VectorIcon(modifier, size, color, unit = 22f) {
        val s = stroke(1.25f)
        val left = Offset(5.5f, 10.99f)
        val topRight = Offset(16.5f, 5.5f)
        val bottomRight = Offset(16.5f, 16.5f)
        drawLine(color, left, topRight, strokeWidth = 1.25f, cap = StrokeCap.Round)
        drawLine(color, left, bottomRight, strokeWidth = 1.25f, cap = StrokeCap.Round)
        listOf(left, topRight, bottomRight).forEach { center ->
            drawCircle(color, radius = 2.0167f, center = center, style = s)
        }
    }
}

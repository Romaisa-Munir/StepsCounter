package com.example.stepscounter.ui.pixel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.stepscounter.DayTotal
import java.text.NumberFormat
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/** Sunset palette. Every colour in the app's pixel UI comes from here. */
object PixelColors {
    val Night = Color(0xFF1F1424)
    val Panel = Color(0xFF33213A)
    val PanelBorder = Color(0xFF5B3A5E)
    val Track = Color(0xFF3D2A45)
    val Cream = Color(0xFFFFF1D6)
    val Sand = Color(0xFFE0B89A)
    val Muted = Color(0xFFB08A9A)
    val Peach = Color(0xFFF7C59F)
    val Amber = Color(0xFFFFB347)
    val Gold = Color(0xFFFFD27A)
    val Rose = Color(0xFFC4504D)
    val Plum = Color(0xFF8C3A50)
}

/**
 * Size of one art pixel in screen pixels. Whole numbers keep every square crisp.
 * [unit] is the grid of the scene; [small] is the font pixel for labels.
 */
data class PixelMetrics(val unit: Int, val small: Int)

val LocalPixel = staticCompositionLocalOf { PixelMetrics(unit = 8, small = 4) }

/** Columns of art pixels across the phone's short side. */
const val GRID_COLUMNS = 96

@Composable
fun rememberPixelMetrics(): PixelMetrics {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val shortSidePx = with(density) { minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp.roundToPx() }
    return remember(shortSidePx) {
        val unit = (shortSidePx / GRID_COLUMNS).coerceAtLeast(2)
        PixelMetrics(unit = unit, small = (unit * 0.55f).roundToInt().coerceAtLeast(2))
    }
}

/** Rectangle with the corner squares cut out: the basic pixel-art panel shape. */
fun DrawScope.drawNotched(topLeft: Offset, size: Size, color: Color, notch: Float) {
    drawRect(color, Offset(topLeft.x + notch, topLeft.y), Size(size.width - 2 * notch, size.height))
    drawRect(color, Offset(topLeft.x, topLeft.y + notch), Size(size.width, size.height - 2 * notch))
}

@Composable
fun PixelText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    pixelPx: Int = LocalPixel.current.small,
    contentDescription: String = text,
) {
    val density = LocalDensity.current
    val shown = text.uppercase()
    val width = with(density) { (PixelFont.width(shown) * pixelPx).toDp() }
    val height = with(density) { (PixelFont.HEIGHT * pixelPx).toDp() }
    Canvas(modifier.size(width, height).semantics { this.contentDescription = contentDescription }) {
        drawPixelText(shown, 0f, 0f, pixelPx.toFloat(), color)
    }
}

@Composable
fun PixelPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val border = LocalPixel.current.small.toFloat()
    Column(
        modifier = modifier
            .drawBehind {
                drawNotched(Offset.Zero, size, PixelColors.PanelBorder, border)
                drawNotched(Offset(border, border), Size(size.width - 2 * border, size.height - 2 * border), PixelColors.Panel, border)
            }
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Chunky button that sinks into its shadow when pressed. */
@Composable
fun PixelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = PixelColors.Cream,
    textColor: Color = PixelColors.Night,
    shadow: Color = PixelColors.Plum,
) {
    val depth = LocalPixel.current.small
    val depthDp = with(LocalDensity.current) { depth.toDp() }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .drawBehind {
                val d = depth.toFloat()
                val face = Size(size.width - d, size.height - d)
                if (pressed) {
                    drawNotched(Offset(d, d), face, fill, d)
                } else {
                    drawNotched(Offset(d, d), face, shadow, d)
                    drawNotched(Offset.Zero, face, fill, d)
                }
            }
            .heightIn(min = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .offset { if (pressed) IntOffset(depth, depth) else IntOffset.Zero }
                .padding(end = depthDp, bottom = depthDp)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            PixelText(text, textColor)
        }
    }
}

/** Goal progress as a row of blocks, like an XP bar. */
@Composable
fun SegmentedBar(fraction: Float, modifier: Modifier = Modifier, segments: Int = 20) {
    val metrics = LocalPixel.current
    val height = with(LocalDensity.current) { (metrics.unit * 4).toDp() }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val b = metrics.small.toFloat()
        drawNotched(Offset.Zero, size, PixelColors.Cream, b)
        drawRect(PixelColors.Night, Offset(b, b), Size(size.width - 2 * b, size.height - 2 * b))
        val areaWidth = size.width - 4 * b
        val segmentWidth = floor((areaWidth - (segments - 1) * b) / segments)
        val used = segmentWidth * segments + (segments - 1) * b
        val left = 2 * b + floor((areaWidth - used) / 2)
        val filled = when {
            fraction >= 1f -> segments
            fraction <= 0f -> 0
            else -> (fraction * segments).toInt().coerceIn(1, segments - 1)
        }
        val color = if (fraction >= 1f) PixelColors.Gold else PixelColors.Amber
        repeat(segments) { i ->
            drawRect(
                if (i < filled) color else PixelColors.Track,
                Offset(left + i * (segmentWidth + b), 2 * b),
                Size(segmentWidth, size.height - 4 * b),
            )
        }
    }
}

/** Seven pixel bars, oldest first, with the goal as a dashed line. */
@Composable
fun WeekChart(days: List<DayTotal>, goal: Int, description: String, modifier: Modifier = Modifier) {
    if (days.isEmpty()) return
    val metrics = LocalPixel.current
    val locale = Locale.getDefault()
    val labels = remember(days, locale) {
        days.map { it.day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale).uppercase(locale) }
    }
    val chartUnits = 22
    val height = with(LocalDensity.current) { (metrics.unit * (chartUnits + 1) + PixelFont.HEIGHT * metrics.small).toDp() }
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val u = metrics.unit.toFloat()
        val s = metrics.small.toFloat()
        val chartHeight = chartUnits * u
        val gap = 2 * u
        val barWidth = floor((size.width - 6 * gap) / 7f)
        val left = floor((size.width - (7 * barWidth + 6 * gap)) / 2)
        val maxValue = maxOf(goal * 1.25f, days.maxOf { it.steps }.toFloat(), 1f)
        fun barHeight(steps: Int) = (floor(steps / maxValue * chartHeight / s) * s).coerceAtLeast(s)

        days.forEachIndexed { i, day ->
            val isToday = i == days.lastIndex
            val h = barHeight(day.steps)
            val color = when {
                day.steps == 0 -> PixelColors.Track
                day.steps >= goal -> PixelColors.Gold
                isToday -> PixelColors.Amber
                else -> PixelColors.Rose
            }
            val x = left + i * (barWidth + gap)
            drawRect(color, Offset(x, chartHeight - h), Size(barWidth, h))
            val label = labels[i]
            val labelX = (x + barWidth / 2 - PixelFont.width(label) * s / 2).roundToInt().toFloat()
            drawPixelText(label, labelX, chartHeight + u, s, if (isToday) PixelColors.Cream else PixelColors.Muted)
        }

        val goalY = chartHeight - barHeight(goal)
        var dashX = left
        val right = left + 7 * barWidth + 6 * gap
        while (dashX < right) {
            drawRect(PixelColors.Peach, Offset(dashX, goalY), Size(minOf(2 * s, right - dashX), s))
            dashX += 4 * s
        }
    }
}

fun weekDescription(days: List<DayTotal>, format: NumberFormat, locale: Locale = Locale.getDefault()): String =
    days.joinToString { "${it.day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} ${format.format(it.steps)}" }

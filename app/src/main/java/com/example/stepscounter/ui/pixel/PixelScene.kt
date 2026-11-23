package com.example.stepscounter.ui.pixel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Smallest scene, in art pixels. Taller screens stretch the sky; everything below it keeps its size. */
const val SCENE_MIN_ROWS = 90

/** Rows below are designed for the minimum scene and shifted down when the sky stretches. */
private const val HORIZON = 70
private const val TOP_BAND_END = 24
private const val GLOW_START = 46

/** Path the walker follows: left edge of the sprite at 0% and at 100% of the goal. */
private const val PATH_START = 4
private const val PATH_LENGTH = 72
private const val FLAG_X = 87
private const val WALKER_TOP = 66
private const val FRAME_MS = 220L

private val SkyBands = listOf(
    0 to Color(0xFF2B1B3D),
    TOP_BAND_END to Color(0xFF3D2147),
    36 to Color(0xFF5E2A4F),
    46 to Color(0xFF8C3A50),
    54 to Color(0xFFC4504D),
    61 to Color(0xFFE87A4D),
    66 to Color(0xFFF7A55B),
)
private val SunOuter = Color(0xFFFFB347)
private val SunInner = Color(0xFFFFD27A)
private val FarHill = Color(0xFF7A2E55)
private val NearHill = Color(0xFF4A2240)
private val GrassTop = Color(0xFFB8C14A)
private val Grass = Color(0xFF7F9B35)
private val PathSand = Color(0xFFC98B4B)
private val PathDark = Color(0xFFA86A35)
private val Soil = Color(0xFF6B3A2E)
private val SoilDark = Color(0xFF55291F)
private val CloudBody = Color(0xFFD9708A)
private val CloudGlow = Color(0xFFF7A55B)
private val FlagRed = Color(0xFFE84855)
private val FlagShade = Color(0xFFB8323F)
private val TextShadow = Color(0xFF140C1A)

private object Walker {
    val palette = mapOf(
        'h' to Color(0xFF3A2418),
        's' to Color(0xFFF5C4A3),
        'e' to Color(0xFF2B1B1B),
        't' to Color(0xFF2EC4B6),
        'p' to Color(0xFF2B3A67),
        'b' to Color(0xFF2B1B1B),
    )
    private val head = listOf("..hhhh..", ".hhhhhh.", ".hhsses.", ".hsssss.", "...ss...")
    val standing = head + listOf("..tttt..", "..ttts..", "..tttt..", "..pppp..", "...pp...", "...pp...", "...bbb..")
    val striding = head + listOf("..tttt..", ".ttttts.", "s.tttt..", "..pppp..", ".pp..pp.", ".p....p.", "bb....bb")
}

/** Star column and height as a fraction of the starry part of the sky. */
private val Stars = listOf(6 to 0.05f, 19 to 0.4f, 33 to 0.1f, 47 to 0.5f, 61 to 0.15f, 75 to 0.45f, 88 to 0.08f, 93 to 0.75f, 3 to 0.9f, 52 to 0.85f)

/**
 * The hero: a sunset where a little walker moves along the path toward the goal flag.
 * Fills whatever size it is given (at least [SCENE_MIN_ROWS] art pixels tall); the walker only
 * animates while new steps arrive.
 */
@Composable
fun PixelScene(
    steps: Int,
    goal: Int,
    walking: Boolean,
    todayLabel: String,
    countText: String,
    goalText: String,
    motivation: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val metrics = LocalPixel.current
    val statusBarBottom = WindowInsets.statusBars.getTop(LocalDensity.current)
    val progress by animateFloatAsState(
        targetValue = (steps.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 1200),
        label = "walker",
    )
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(walking) {
        frame = 0
        while (walking) {
            delay(FRAME_MS)
            frame = 1 - frame
        }
    }
    val today = todayLabel.uppercase()
    val count = countText.uppercase()
    val goalLine = goalText.uppercase()
    val cheer = motivation.uppercase()

    Canvas(modifier.semantics { contentDescription = description }) {
        val u = metrics.unit.toFloat()
        val x0 = floor((size.width - GRID_COLUMNS * u) / 2f)
        // Extra rows on tall screens all go into the sky.
        val extra = (floor(size.height / u).toInt() - SCENE_MIN_ROWS).coerceAtLeast(0)
        fun cell(x: Int, y: Int, w: Int, h: Int, color: Color) =
            drawRect(color, Offset(x0 + x * u, (y + extra) * u), Size(w * u, h * u))
        fun band(fromY: Float, toY: Float, color: Color) =
            drawRect(color, Offset(0f, fromY), Size(size.width, toY - fromY))
        val firstColumn = floor(-x0 / u).toInt() - 1
        val lastColumn = GRID_COLUMNS + ceil(x0 / u).toInt() + 1

        // The night bands above the sunset glow share the extra height; the glow keeps its size.
        fun skyRow(row: Int) = if (row <= GLOW_START) row + extra * row / GLOW_START else row + extra
        SkyBands.forEachIndexed { i, (row, color) ->
            val to = skyRow(SkyBands.getOrNull(i + 1)?.first ?: HORIZON) * u
            band(skyRow(row) * u, to, color)
        }

        drawDisc(::cell, cx = 24, cy = 64, radius = 8, color = SunOuter)
        drawDisc(::cell, cx = 24, cy = 64, radius = 6, color = SunInner)

        cell(9, 52, 6, 1, CloudBody); cell(6, 53, 12, 1, CloudBody); cell(4, 54, 16, 1, CloudGlow)
        cell(71, 55, 6, 1, CloudBody); cell(68, 56, 13, 1, CloudBody); cell(66, 57, 17, 1, CloudGlow)

        for (x in firstColumn..lastColumn) {
            val far = minOf(60 + abs(x - 14) / 2, 59 + abs(x - 58) / 2, 62 + abs(x - 93) / 2)
            if (far < HORIZON) cell(x, far, 1, HORIZON - far, FarHill)
        }
        for (x in firstColumn..lastColumn) {
            val near = minOf(65 + abs(x - 36) / 3, 64 + abs(x - 77) / 3)
            if (near < HORIZON) cell(x, near, 1, HORIZON - near, NearHill)
        }

        band((70 + extra) * u, (71 + extra) * u, GrassTop)
        band((71 + extra) * u, (74 + extra) * u, Grass)
        band((74 + extra) * u, (80 + extra) * u, PathSand)
        band((80 + extra) * u, size.height, Soil)
        listOf(12 to 79, 31 to 75, 50 to 78, 70 to 76, 91 to 79).forEach { (x, y) -> cell(x, y, 1, 1, PathDark) }
        listOf(8 to 83, 25 to 87, 44 to 84, 63 to 88, 85 to 85).forEach { (x, y) -> cell(x, y, 2, 1, SoilDark) }

        listOf(0.25f, 0.5f, 0.75f).forEach { m ->
            cell(PATH_START + (m * PATH_LENGTH).roundToInt() + 4, 70, 1, 3, PixelColors.Cream)
        }

        cell(FLAG_X, 55, 1, 1, PixelColors.Gold)
        cell(FLAG_X, 56, 1, 18, PixelColors.Cream)
        cell(FLAG_X + 1, 56, 6, 3, FlagRed)
        cell(FLAG_X + 1, 59, 4, 1, FlagShade)

        val walkerX = PATH_START + (progress * PATH_LENGTH).roundToInt()
        var print = PATH_START + 2
        while (print < walkerX) {
            cell(print, if ((print / 4) % 2 == 0) 76 else 78, 2, 1, PathDark)
            print += 4
        }
        if (progress >= 1f) {
            listOf(82 to 52, 92 to 50, 78 to 58).forEach { (x, y) ->
                cell(x, y - 1, 1, 3, PixelColors.Gold)
                cell(x - 1, y, 3, 1, PixelColors.Gold)
            }
        }
        val sprite = if (frame == 1) Walker.striding else Walker.standing
        drawPixelRows(sprite, x0 + walkerX * u, (WALKER_TOP + extra) * u, u) { Walker.palette[it] }

        // Text block, centred in the open sky between the status bar and the clouds.
        val small = metrics.small.toFloat()
        val usable = (GRID_COLUMNS - 8) * u
        val big = floor(minOf(3 * u, usable / PixelFont.width(count).coerceAtLeast(1)))
        val gap = 2 * u
        val blockHeight = PixelFont.HEIGHT * (small * 3 + big) + gap * 3
        val minTop = maxOf(14 * u, statusBarBottom + 3 * u)
        val maxBottom = (53 + extra) * u
        val todayY = floor(minTop + ((maxBottom - minTop - blockHeight) / 2f).coerceAtLeast(0f))
        fun centered(text: String, pixel: Float) = floor((size.width - PixelFont.width(text) * pixel) / 2f)

        val widest = maxOf(PixelFont.width(count) * big, PixelFont.width(cheer) * small, PixelFont.width(goalLine) * small)
        val textLeft = (size.width - widest) / 2f - 2 * u
        val textRight = (size.width + widest) / 2f + 2 * u
        val textTop = todayY - 2 * u
        val textBottom = todayY + blockHeight + 2 * u
        val starTop = statusBarBottom + 2 * u
        val starBottom = skyRow(GLOW_START - 4) * u
        Stars.forEachIndexed { i, (x, at) ->
            val sx = x0 + x * u
            val sy = floor((starTop + at * (starBottom - starTop)) / u) * u
            val behindText = sx + u > textLeft && sx < textRight && sy + u > textTop && sy < textBottom
            if (!behindText) drawRect(if (i % 2 == 0) PixelColors.Cream else PixelColors.Peach, Offset(sx, sy), Size(u, u))
        }

        drawPixelText(today, centered(today, small), todayY, small, PixelColors.Peach)
        val countY = todayY + PixelFont.HEIGHT * small + gap
        val countX = centered(count, big)
        drawPixelText(count, countX + small, countY + small, big, TextShadow)
        drawPixelText(count, countX, countY, big, PixelColors.Cream)
        val goalY = countY + PixelFont.HEIGHT * big + gap
        drawPixelText(goalLine, centered(goalLine, small), goalY, small, PixelColors.Sand)
        val cheerY = goalY + PixelFont.HEIGHT * small + gap
        drawPixelText(cheer, centered(cheer, small), cheerY, small, PixelColors.Gold)
    }
}

/** Pixel-art filled circle, row by row. */
private inline fun drawDisc(
    cell: (Int, Int, Int, Int, Color) -> Unit,
    cx: Int,
    cy: Int,
    radius: Int,
    color: Color,
) {
    for (dy in -radius..radius) {
        val half = floor(sqrt((radius + 0.5) * (radius + 0.5) - dy * dy)).toInt()
        cell(cx - half, cy + dy, 2 * half + 1, 1, color)
    }
}

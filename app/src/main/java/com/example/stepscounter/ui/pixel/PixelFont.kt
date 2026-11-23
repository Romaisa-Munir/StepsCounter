package com.example.stepscounter.ui.pixel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * A 5×7 bitmap font drawn as squares, so labels sit on the same pixel grid as the artwork.
 * Uppercase only: lowercase is drawn as uppercase, unknown characters as '?'.
 */
object PixelFont {
    const val HEIGHT = 7
    private const val SPACING = 1

    private val glyphs: Map<Char, List<String>> = mapOf(
        '0' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to listOf("#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."),
        '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to listOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        'A' to listOf(".###.", "#...#", "#...#", "#...#", "#####", "#...#", "#...#"),
        'B' to listOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to listOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
        'D' to listOf("###..", "#..#.", "#...#", "#...#", "#...#", "#..#.", "###.."),
        'E' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'F' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'G' to listOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"),
        'H' to listOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'I' to listOf(".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."),
        'J' to listOf("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
        'K' to listOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'L' to listOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to listOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'N' to listOf("#...#", "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#"),
        'O' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to listOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'Q' to listOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'R' to listOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'S' to listOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'T' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'W' to listOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "#.#.#", ".#.#."),
        'X' to listOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to listOf("#...#", "#...#", "#...#", ".#.#.", "..#..", "..#..", "..#.."),
        'Z' to listOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        ' ' to listOf("...", "...", "...", "...", "...", "...", "..."),
        ',' to listOf("..", "..", "..", "..", "..", ".#", "#."),
        '.' to listOf(".", ".", ".", ".", ".", ".", "#"),
        '!' to listOf("#", "#", "#", "#", "#", ".", "#"),
        '\'' to listOf("#", "#", ".", ".", ".", ".", "."),
        ':' to listOf(".", ".", "#", ".", "#", ".", "."),
        '-' to listOf("...", "...", "...", "###", "...", "...", "..."),
        '/' to listOf("....#", "....#", "...#.", "..#..", ".#...", "#....", "#...."),
        '%' to listOf("##..#", "##..#", "...#.", "..#..", ".#...", "#..##", "#..##"),
        '?' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."),
        '✓' to listOf(".....", "....#", "...##", "#.##.", "###..", ".#...", "....."),
        '•' to listOf(".....", ".....", ".###.", ".###.", ".###.", ".....", "....."),
    )

    fun glyph(c: Char): List<String> = glyphs[c.uppercaseChar()] ?: glyphs.getValue('?')

    /** Width in font pixels. */
    fun width(text: String): Int =
        if (text.isEmpty()) 0 else text.sumOf { glyph(it)[0].length } + SPACING * (text.length - 1)

    internal fun advance(c: Char): Int = glyph(c)[0].length + SPACING
}

/** Draws [rows] of characters as pixels; [colorOf] returns null for transparent cells. */
fun DrawScope.drawPixelRows(rows: List<String>, x: Float, y: Float, pixel: Float, colorOf: (Char) -> Color?) {
    rows.forEachIndexed { r, line ->
        var c = 0
        while (c < line.length) {
            val color = colorOf(line[c])
            if (color == null) {
                c++
                continue
            }
            var end = c + 1
            while (end < line.length && line[end] == line[c]) end++
            drawRect(color, Offset(x + c * pixel, y + r * pixel), Size((end - c) * pixel, pixel))
            c = end
        }
    }
}

fun DrawScope.drawPixelText(text: String, x: Float, y: Float, pixel: Float, color: Color) {
    var cursor = x
    for (c in text) {
        drawPixelRows(PixelFont.glyph(c), cursor, y, pixel) { if (it == '#') color else null }
        cursor += PixelFont.advance(c) * pixel
    }
}

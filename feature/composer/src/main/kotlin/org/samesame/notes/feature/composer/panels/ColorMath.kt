package org.samesame.notes.feature.composer.panels

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min

/** Pure colour helpers used by the picker. Hue 0..360, saturation/value 0..1. */
internal data class Hsv(val h: Float, val s: Float, val v: Float)

internal fun Color.toHsv(): Hsv {
    val r = red; val g = green; val b = blue
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
    val h = when {
        d == 0f -> 0f
        mx == r -> 60f * (((g - b) / d) % 6f)
        mx == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return Hsv(h, if (mx == 0f) 0f else d / mx, mx)
}

internal fun hsvColor(h: Float, s: Float, v: Float): Color = Color.hsv(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))

internal fun Color.toRgb255(): Triple<Int, Int, Int> =
    Triple((red * 255f + 0.5f).toInt(), (green * 255f + 0.5f).toInt(), (blue * 255f + 0.5f).toInt())

internal fun Color.toHex6(): String {
    val (r, g, b) = toRgb255()
    return "%02X%02X%02X".format(r, g, b)
}

/** Parses "RRGGBB" (optional leading #); returns null when invalid. */
internal fun parseHex6(text: String): Color? {
    val t = text.trim().removePrefix("#")
    if (t.length != 6) return null
    val v = t.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or v)
}

/** Swatch grid of the picker (`#43`): 13 columns by 12 rows; column 0 is greys, the rest are hues. */
internal object SwatchGrid {
    const val COLUMNS = 13
    const val ROWS = 12
    private val hues = floatArrayOf(0f, 25f, 43f, 58f, 95f, 140f, 165f, 190f, 215f, 260f, 300f, 335f)

    fun colorAt(column: Int, row: Int): Color {
        if (column == 0) {
            val v = 1f - row / (ROWS - 1f)
            return Color(v, v, v)
        }
        val l = 0.95f - row * 0.075f
        return hslToColor(hues[column - 1], 1f, l)
    }

    private fun hslToColor(h: Float, s: Float, l: Float): Color {
        val a = s * min(l, 1f - l)
        fun f(n: Int): Float {
            val k = (n + h / 30f) % 12f
            return l - a * max(-1f, min(min(k - 3f, 9f - k), 1f))
        }
        return Color(f(0).coerceIn(0f, 1f), f(8).coerceIn(0f, 1f), f(4).coerceIn(0f, 1f))
    }
}

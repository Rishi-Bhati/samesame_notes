package org.samesame.notes.core.ink

import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke

/**
 * Display-time ink color adjustment for dark pages, so dark writing reads light (black becomes white).
 *
 * Colors darker than mid lightness get their HSL lightness inverted (hue and saturation kept: every channel shifts by
 * `255 - max - min`). Colors at or above mid lightness are shown as picked, so the default blue pen (#335FFF) looks the
 * same on a dark page as on a light one. The mapping is not reversible: to store what the user drew, keep the picked
 * brush instead of inverting the displayed one. Notes always store the colors the user picked.
 */
object DarkModeInk {
    fun invert(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val shift = 255 - maxOf(r, g, b) - minOf(r, g, b)
        if (shift <= 0) return argb
        return (argb and 0xFF000000.toInt()) or ((r + shift) shl 16) or ((g + shift) shl 8) or (b + shift)
    }

    fun invert(brush: Brush): Brush = brush.copyWithColorIntArgb(invert(brush.colorIntArgb))

    fun invert(stroke: Stroke): Stroke = stroke.copy(invert(stroke.brush))
}

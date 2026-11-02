package org.samesame.notes.feature.composer.toolbar

import androidx.ink.brush.Brush
import org.samesame.notes.core.data.repository.EditorPreferences
import org.samesame.notes.core.ink.brush.PenCatalog
import org.samesame.notes.core.ink.brush.PenSpec
import kotlin.math.roundToInt

/**
 * The brush the selected pen tool paints with: the [spec] (family and tip geometry), the size [level], the picked
 * [color] and the effective [alpha] (the pen's forced opacity, or the highlighter's opacity). [argb] is the colour the
 * stroke is stored with, so the live (in-progress) stroke and the committed one come from the same value.
 *
 * Everything but [brush] is pure, so the selection can be tested on the JVM.
 */
internal data class PenStyle(
    val spec: PenSpec,
    /** Size slider level (1..100). */
    val level: Int,
    /** Picked colour, without [alpha]. */
    val color: Long,
    /** Overrides the pen's forced alpha (highlighter opacity, 0..255). */
    val alpha: Int? = null,
    val pressure: Boolean = true,
    /** Straight-line highlighter tips snap the finished stroke to the line between its first and last point. */
    val straightLine: Boolean = false,
) {
    /** Colour painted: [color] with [alpha] (or the pen's forced opacity) applied. */
    val argb: Long get() = spec.colorFor(color, alpha)

    /** Width in page units at [level]. */
    val widthPu: Float get() = spec.widthPu(level)

    fun brush(): Brush = spec.brush(level, color, pressure, alpha)
}

/** Resolves the tool's brush from the persisted tool settings; shared by the composer and the drawing sheet. */
internal object PenStyles {
    fun current(tool: PenModeTool, prefs: EditorPreferences): PenStyle =
        if (tool == PenModeTool.Highlighter) {
            PenStyle(
                spec = PenCatalog.highlighterFor(prefs.highlighterTip),
                level = prefs.highlighterLevel,
                color = prefs.highlighterColor,
                alpha = alpha(prefs.highlighterOpacity),
                // Both highlighter families ignore pressure; true keeps the stored family key unchanged.
                pressure = true,
                straightLine = PenCatalog.isStraightTip(prefs.highlighterTip),
            )
        } else {
            PenStyle(
                spec = PenCatalog.specFor(prefs.penType),
                level = prefs.penLevel,
                color = prefs.penColor,
                pressure = prefs.penPressureSensitive,
            )
        }

    /** Highlighter opacity in percent as the 0..255 alpha its brush is created with. */
    fun alpha(opacity: Int): Int = (opacity.coerceIn(0, 100) * 255 / 100f).roundToInt()
}

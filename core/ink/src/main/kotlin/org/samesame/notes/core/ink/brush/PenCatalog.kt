package org.samesame.notes.core.ink.brush

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import org.samesame.notes.core.ink.PenBrushes
import org.samesame.notes.core.model.PenType
import kotlin.math.pow

/**
 * One entry of the pen panel. Levels are the UI slider value; [widthPu] is the brush size in page units.
 * Defaults are provisional until a calibration capture exists (docs 4.1 / 8).
 */
data class PenSpec(
    val type: PenType,
    val sizeLevels: IntRange,
    val defaultLevel: Int,
    val defaultColor: Long,
    val fixedAlpha: Int?,
    /** Round marker tip (stored under [PenBrushes.ROUND_MARKER_KEY]); only meaningful for the highlighter kind. */
    val roundTip: Boolean = false,
    val build: (pressure: Boolean) -> BrushFamily,
) {
    /** Level to width: level 30 is about 5 pu (reference #45), power curve 0.8 so thin levels stay usable. */
    fun widthPu(level: Int): Float =
        REFERENCE_WIDTH_PU * (level.coerceIn(sizeLevels).toFloat() / REFERENCE_LEVEL).pow(0.8f)

    /** The ARGB painted for [colorArgb]: the pen's forced alpha, or [alpha] (0..255) when given (highlighter opacity). */
    fun colorFor(colorArgb: Long = defaultColor, alpha: Int? = null): Long =
        (alpha ?: fixedAlpha)?.let { (colorArgb and 0x00FFFFFF) or (it.toLong() shl 24) } ?: colorArgb

    /** Brush for [level]; translucent pens get their forced alpha, or [alpha] (0..255) when given (highlighter opacity). */
    fun brush(level: Int, colorArgb: Long = defaultColor, pressure: Boolean = true, alpha: Int? = null): Brush =
        Brush.createWithColorIntArgb(
            if (roundTip) PenBrushes.markerFamily() else PenBrushes.familyFor(type, pressure),
            colorFor(colorArgb, alpha).toInt(), widthPu(level), 0.1f,
        )

    private companion object {
        const val REFERENCE_LEVEL = 30f
        const val REFERENCE_WIDTH_PU = 5f
    }
}

object PenCatalog {
    private const val DEFAULT_COLOR = 0xFF000000

    private fun spec(type: PenType, level: Int = 30, levels: IntRange = 1..100) = PenSpec(
        type, levels, level, DEFAULT_COLOR, type.fixedAlpha, false,
    ) { pressure -> PenBrushes.familyFor(type, pressure) }

    /** Panel order (#33): fountain, calligraphy, pen, pencil, brush. */
    val pens: List<PenSpec> = listOf(PenType.Fountain, PenType.Calligraphy, PenType.Pen, PenType.Pencil, PenType.Brush)
        .map { spec(it) }

    val highlighter: PenSpec = spec(PenType.Highlighter, level = 50)

    /** The round "Marker pen" tip of the highlighter panel (tips 3 and 4); same width scale and alpha as [highlighter]. */
    val marker: PenSpec = highlighter.copy(roundTip = true)

    /**
     * Spec for highlighter-panel tip [tip] (0 highlighter, 1 straight-line highlighter, 2 marker pen, 3 straight-line marker):
     * tips 0/1 use the flat tip, 2/3 the round one.
     */
    fun highlighterFor(tip: Int): PenSpec = if (tip >= 2) marker else highlighter

    /** Tips 1 and 3 snap their stroke to a straight line. */
    fun isStraightTip(tip: Int): Boolean = tip == 1 || tip == 3

    fun specFor(type: PenType): PenSpec = if (type == PenType.Highlighter) highlighter else pens.first { it.type == type }
}

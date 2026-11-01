package org.samesame.notes.core.richtext

import org.samesame.notes.core.model.Limits

/**
 * Body text measurements in page units. Measured on the reference captures (#26, #73, #74; 1080 px
 * wide, 1 pu = 1 px) unless marked provisional.
 */
object BodyTextMetrics {
    /** Doc §1.1 (provisional there). #73: the "L" stem starts at x≈52 px, i.e. 47 + side bearing. */
    const val MARGIN_LEFT = 47f
    const val MARGIN_RIGHT = 47f
    const val MARGIN_TOP = 32f
    /** Measured from #107: exactly 20 lines of size 17 fit a 1527 px page (needs > 52 px); 96 keeps that stable. */
    const val MARGIN_BOTTOM = 96f
    val COLUMN_WIDTH: Float = Limits.PAGE_WIDTH_PU - MARGIN_LEFT - MARGIN_RIGHT

    /**
     * the reference's text face has larger glyphs than Roboto at the same nominal size: #73/#107 "Line 1"
     * is 37-38 px tall and 159 px wide at size 17, ours (Roboto at 44.6 px) is 33 px tall and 138 px
     * wide, i.e. ~1.14×. Scale our glyphs to match while keeping the model size ("17") unchanged.
     */
    const val GLYPH_SCALE = 1.14f

    /** Default text size in pu ("17" × 2.625 × [GLYPH_SCALE]). */
    val DEFAULT_TEXT_SIZE_PU: Float =
        Limits.DEFAULT_FONT_SIZE * Limits.FONT_PU_PER_POINT * GLYPH_SCALE

    /**
     * #107: line pitch is exactly 69 px (20 lines per A4 page). Roboto's natural line height at the
     * scaled size (50.9 px) is ~59.6 px, so ×1.16 gives 69.2 px each line — same break after line 20.
     */
    const val LINE_SPACING_MULT = 1.16f

    /** #74: checkbox left edge 8 px right of the margin, text starts 80 px right of the margin. */
    const val MARKER_WIDTH = 80f
    const val MARKER_INSET = 6.5f

    /** Checkbox: 46 px square at size 17 (#74) → 0.9 em of the scaled font; 4 px stroke → 0.08 em; centre 0.35 em above baseline. */
    /** A checked item: text, box and tick at 40 % of the text colour, strike-through at 63 % (measured on the reference). */
    const val CHECKED_ALPHA = 0.4f
    const val CHECKED_STRIKE_ALPHA = 0.63f
    const val CHECKBOX_SIZE_EM = 0.978f
    const val CHECKBOX_STROKE_EM = 0.08f
    const val CHECKBOX_CORNER_EM = 0.18f
    const val MARKER_CENTER_ABOVE_BASELINE_EM = 0.35f
    const val BULLET_RADIUS_EM = 0.114f

    /** Measured on the reference: a checklist item's box moves 18.3 dp per indent level (48 px at 2.625 px/dp). */
    const val INDENT_STEP = 48f

    /** #74: caret is ~4 px wide (colour #FF5E36 in dark mode). */
    const val CARET_WIDTH = 4f

    /** Fallback size for an inline object whose provider doesn't know it yet. */
    const val PLACEHOLDER_OBJECT_SIZE = 240f
}

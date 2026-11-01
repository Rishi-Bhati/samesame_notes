package org.samesame.notes.core.richtext

import kotlin.math.ceil

/**
 * Body text on a ruled page (narrow / medium / wide lines, grids, dots) sits on the template rules: every line
 * box is a whole number of rules tall ([lineHeight]) so no line drifts off its rule, and each page's first
 * baseline is moved onto the page's first rule ([pageShift]).
 *
 * The rules repeat every [pitch] pu from the page top; the text column keeps its usual left/right margins. A
 * taller font (heading) simply occupies two or more rules. Pages without a ruled template are untouched.
 */
object TemplateLineAlignment {

    /** Height of a line whose natural font height is [natural], rounded up to a whole number of rules. */
    fun lineHeight(natural: Float, pitch: Float): Float {
        if (pitch <= 0f || natural <= 0f) return natural
        val slots = ceil(natural / pitch).toInt().coerceAtLeast(1)
        return slots * pitch
    }

    /**
     * Extra vertical shift (pu) for the text of a page: the first line's baseline must land on the first rule,
     * `pageTop + firstRule` (see `TextPageGeometry.firstRuleOffset`). [firstBaseline] is the layout baseline of
     * the page's first line, [contentTop] and [columnTop] are that line's page geometry (the usual
     * `content.top - columnTop` translation).
     */
    fun pageShift(firstRule: Float, pageTop: Float, contentTop: Float, columnTop: Float, firstBaseline: Float): Float =
        pageTop + firstRule - (contentTop - columnTop + firstBaseline)
}

package org.samesame.notes.feature.composer.easypad

import androidx.compose.ui.geometry.Offset

/**
 * Where the pad's text line is on a page (page-local page units): the line of the text cursor. [x] is where the next handwriting
 * group starts; [contentLeft]/[contentRight] bound the text column.
 */
data class PadSlot(
    val pageIndex: Int,
    val x: Float,
    val lineTop: Float,
    val lineHeight: Float,
    val contentLeft: Float,
    val contentRight: Float,
)

/**
 * Placement rules of the easy writing pad (own design: the reference app's pad logic is native code, so only its documented
 * behaviour is matched: handwriting written in the magnified pad lands neatly on the text cursor's line, Next moves on,
 * Previous steps back, Enter starts a new line).
 *
 *  - The pad's writing area shows a band 1.4 line heights tall ([BAND]) starting 0.2 line heights above the line ([BAND_TOP]);
 *    pad pixels / [scale] = page units, so the pad is a magnifier of about 4x and writing the height of the area's baseline-to-top
 *    fills the line.
 *  - A group of strokes keeps the layout drawn in the pad, but its left edge is moved to the slot's x.
 *  - After a group the slot advances by the group width plus [GAP] line heights; when less than two line heights of column
 *    remain the text wraps to a new line (the caller presses Enter).
 */
object EasyPadMath {
    const val BAND = 1.4f
    const val BAND_TOP = 0.2f
    const val GAP = 0.25f

    /** Ink that reaches this fraction of the pad width is committed at once (the marker line in the pad). */
    const val COMMIT_FRACTION = 0.92f

    /** Idle time after the last stroke before the group is committed. */
    const val IDLE_COMMIT_MS = 1500L

    /** Pad pixels per page unit for a writing area [areaHeightPx] tall and a line [lineHeight] units tall. */
    fun scale(areaHeightPx: Float, lineHeight: Float): Float = areaHeightPx / (BAND * lineHeight.coerceAtLeast(1f))

    /** Pad y of the text baseline (0.8 of the line height below the line top). */
    fun baselineY(areaHeightPx: Float): Float = areaHeightPx / BAND

    /** Maps a pad point to page units for a group whose left edge in the pad is [anchorPadX]. */
    fun toPage(padX: Float, padY: Float, anchorPadX: Float, slot: PadSlot, scale: Float): Offset =
        Offset(slot.x + (padX - anchorPadX) / scale, slot.lineTop - BAND_TOP * slot.lineHeight + padY / scale)

    /** New x after a group [groupWidthPu] wide. */
    fun advance(slot: PadSlot, groupWidthPu: Float): Float = slot.x + groupWidthPu + GAP * slot.lineHeight

    /** A bare "next" with nothing written inserts a space this wide. */
    fun spaceWidth(slot: PadSlot): Float = 0.3f * slot.lineHeight

    /**
     * Where a pad Enter inserts its newline: the end of the caret's paragraph (the first `\n` at or after [caret], or the end of
     * [text]). The handwriting sits on the caret's line, so a newline before that line would push the text away from its ink.
     */
    fun enterOffset(text: CharSequence, caret: Int): Int {
        for (i in caret.coerceIn(0, text.length) until text.length) if (text[i] == '\n') return i
        return text.length
    }

    /** True when the next group would have under two line heights of room left, so the text should wrap. */
    fun needsWrap(x: Float, slot: PadSlot): Boolean = x > slot.contentRight - 2f * slot.lineHeight
}

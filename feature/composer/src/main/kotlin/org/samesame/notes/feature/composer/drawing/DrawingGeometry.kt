package org.samesame.notes.feature.composer.drawing

import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.document.ObjectLimits
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.model.document.SizePu

/** Pure geometry of the drawing sheet (Insert > Drawing). */
internal object DrawingGeometry {
    /** Page id of the one page of the sheet's in-memory document. */
    const val SHEET_PAGE_ID = "drawing-sheet"

    /** The sheet is the page width wide and 1.5x that tall. */
    val sheet: SizePu = SizePu(Limits.PAGE_WIDTH_PU, Limits.PAGE_WIDTH_PU * ObjectLimits.DRAWING_SHEET_HEIGHT_OVER_WIDTH)

    /**
     * The region the thumbnail covers: the union of [bounds] plus [margin] on every side, kept inside the sheet.
     * Null when there is nothing to cover.
     */
    fun contentRect(bounds: List<RectPu>, margin: Float = ObjectLimits.DRAWING_THUMB_MARGIN_PU, size: SizePu = sheet): RectPu? {
        if (bounds.isEmpty()) return null
        val l = (bounds.minOf { it.left } - margin).coerceAtLeast(0f)
        val t = (bounds.minOf { it.top } - margin).coerceAtLeast(0f)
        val r = (bounds.maxOf { it.right } + margin).coerceAtMost(size.width)
        val b = (bounds.maxOf { it.bottom } + margin).coerceAtMost(size.height)
        if (r - l < 1f || b - t < 1f) return null
        return RectPu(l, t, r, b)
    }

    /** What the Done button does. */
    enum class Outcome { Unchanged, NothingToSave, Save }

    /** Done: unchanged -> close silently; changed but empty -> "Nothing to save" toast; otherwise save. */
    fun outcome(changed: Boolean, strokeCount: Int): Outcome = when {
        !changed -> Outcome.Unchanged
        strokeCount == 0 -> Outcome.NothingToSave
        else -> Outcome.Save
    }

    /** What the back arrow and system back do. */
    enum class DiscardOutcome { Silent, Confirm }

    /**
     * Back discards (Done is the only save path). Nothing worth keeping leaves silently; otherwise the sheet asks first.
     * A re-edited drawing only ever discards its unsaved changes, because the original object is left untouched.
     */
    fun discardOutcome(changed: Boolean, strokeCount: Int): DiscardOutcome =
        if (changed && strokeCount > 0) DiscardOutcome.Confirm else DiscardOutcome.Silent
}

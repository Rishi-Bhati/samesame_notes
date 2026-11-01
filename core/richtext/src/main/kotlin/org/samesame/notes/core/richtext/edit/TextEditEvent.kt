package org.samesame.notes.core.richtext.edit

import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.RichTextOps

/** A selection in body offsets; `start == end` is a caret. `start` may be greater than `end`. */
data class TextSelection(val start: Int, val end: Int = start) {
    val min: Int get() = minOf(start, end)
    val max: Int get() = maxOf(start, end)
    val collapsed: Boolean get() = start == end
}

/**
 * Edits reported by the engine, one per user action (doc §4.4). History (WP-H) wraps them in
 * commands and undoes/redoes them with `BodyText.undo(event)` / `BodyText.redo(event)`.
 *
 * Fragments are "touched slices" (`RichTextOps.slice` semantics): their `paragraphs` are the exact
 * styles of every paragraph the range touches, so applying them back restores paragraph styles too.
 */
sealed interface TextEditEvent {

    /**
     * `[start, start + removed.length)` was replaced by [inserted]. IME composition never yields
     * intermediate events: a whole composition (and everything in one IME batch) is one event, with
     * [composed] set.
     */
    data class TextEdit(
        val start: Int,
        val removed: RichText,
        val inserted: RichText,
        val selectionBefore: TextSelection,
        val selectionAfter: TextSelection,
        val composed: Boolean = false,
    ) : TextEditEvent {

        /**
         * Coalesces [next] (which happened right after this edit) into one edit, or null when they
         * aren't contiguous typing/backspacing. WP-H decides *when* a burst breaks (pause, whitespace,
         * cursor jump, style change); this only does the arithmetic.
         */
        fun mergeWith(next: TextEdit): TextEdit? {
            val end = start + inserted.length
            return when {
                // Typing on: next inserts right where this one ended.
                next.removed.length == 0 && next.start == end ->
                    copy(inserted = concat(inserted, next.inserted, joinFromSecond = true), selectionAfter = next.selectionAfter)

                // Backspace into what this edit inserted.
                next.inserted.length == 0 && next.start >= start && next.start + next.removed.length == end ->
                    copy(inserted = RichTextOps.slice(inserted, 0, next.start - start), selectionAfter = next.selectionAfter)

                // Backspace past the start of this edit: everything this edit inserted is gone again.
                next.inserted.length == 0 && next.start < start && next.start + next.removed.length == end ->
                    TextEdit(
                        start = next.start,
                        removed = concat(RichTextOps.slice(next.removed, 0, start - next.start), removed, joinFromSecond = true),
                        inserted = next.inserted,
                        selectionBefore = selectionBefore,
                        selectionAfter = next.selectionAfter,
                        composed = composed || next.composed,
                    )

                // Forward delete burst at the same caret.
                inserted.length == 0 && next.inserted.length == 0 && next.start == start ->
                    copy(removed = concat(removed, next.removed, joinFromSecond = false), selectionAfter = next.selectionAfter)

                else -> null
            }
        }

        fun inverse(): TextEdit = TextEdit(start, inserted, removed, selectionAfter, selectionBefore, composed)
    }

    /** Character styles of `[start, start + before.length)` changed (text unchanged). */
    data class CharStyleChange(
        val start: Int,
        val before: RichText,
        val after: RichText,
        val selection: TextSelection,
    ) : TextEditEvent

    /** Paragraphs `firstParagraph until firstParagraph + before.size` changed style. */
    data class ParagraphStyleChange(
        val firstParagraph: Int,
        val before: List<ParagraphStyle>,
        val after: List<ParagraphStyle>,
        val selection: TextSelection,
    ) : TextEditEvent

    companion object {
        /**
         * Joins two touched slices where [a] ends in the paragraph [b] starts in. The shared
         * paragraph's style is taken from [b] when [joinFromSecond], else from [a].
         */
        fun concat(a: RichText, b: RichText, joinFromSecond: Boolean): RichText {
            val joint = if (joinFromSecond) b.paragraphs.first() else a.paragraphs.last()
            return RichTextOps.normalise(
                RichText(
                    text = a.text + b.text,
                    runs = a.runs + b.runs,
                    paragraphs = a.paragraphs.dropLast(1) + joint + b.paragraphs.drop(1),
                ),
            )
        }
    }
}

/**
 * Accumulates raw text changes (one `SpannableStringBuilder` replace each) into one [TextEditEvent.TextEdit]
 * per batch/composition. [slice] returns the touched slice of the *current* text.
 */
class EditAccumulator(private val slice: (start: Int, end: Int) -> RichText) {

    class Pending(
        val start: Int,
        val removed: RichText,
        /** Current length of the edited region. */
        val length: Int,
        val selectionBefore: TextSelection,
        val composed: Boolean,
    )

    var pending: Pending? = null
        private set

    /**
     * Call before `[start, start + count)` is replaced by [after] characters. If the change is not
     * contiguous with the pending region, the pending edit is returned so the caller completes it
     * (the text is still in its pre-change state) and a new one starts.
     */
    fun before(start: Int, count: Int, after: Int, selection: TextSelection, composing: Boolean): Pending? {
        val p = pending
        val end = start + count
        if (p == null) {
            pending = Pending(start, slice(start, end), after, selection, composing)
            return null
        }
        val pEnd = p.start + p.length
        if (start > pEnd || end < p.start) {
            pending = Pending(start, slice(start, end), after, selection, composing)
            return p
        }
        val newStart = minOf(p.start, start)
        val newEnd = maxOf(pEnd, end)
        // Outside the pending region the text is still original.
        val prefix = slice(newStart, p.start)
        val suffix = slice(pEnd, newEnd)
        val removed = TextEditEvent.concat(
            TextEditEvent.concat(prefix, p.removed, joinFromSecond = true),
            suffix,
            joinFromSecond = false,
        )
        pending = Pending(newStart, removed, (newEnd - newStart) - count + after, p.selectionBefore, p.composed || composing)
        return null
    }

    fun take(): Pending? = pending.also { pending = null }
}

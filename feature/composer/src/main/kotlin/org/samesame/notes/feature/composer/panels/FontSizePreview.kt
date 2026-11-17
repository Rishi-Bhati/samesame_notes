package org.samesame.notes.feature.composer.panels

import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.RichTextOps
import org.samesame.notes.core.richtext.BodyText
import org.samesame.notes.core.richtext.RichTextBridge

/**
 * Live preview of a font-size glide in the note: the selected text is drawn at the highlighted size while the finger
 * is down, without editing. The character-style spans of the selected range are swapped directly
 * ([RichTextBridge.setRuns]); that is a span-only change, so the body emits no edit event: no history entry, nothing
 * marks the note dirty, the autosaver does not run. [restore] puts the original runs back; the release then applies
 * ONE normal `applyCharStyle` (one undo step). At a caret there is nothing to preview (the typing style has no glyphs).
 */
class FontSizePreview(private val body: BodyText) {

    private var start = -1
    private var before: RichText? = null
    private var length = -1

    /** True while the body shows a preview. */
    val active: Boolean get() = before != null

    /** Shows [pick] on the selection (no-op at a caret); null restores the text. */
    fun show(pick: FontSizePick?) {
        if (pick == null) { restore(); return }
        val (size, bold) = style(pick)
        val original = before ?: snapshot() ?: return
        val after = RichTextOps.applyCharStyle(original, 0, original.length) { it.copy(fontSize = size, bold = bold) }
        if (!intact()) { drop(); return }
        body.batch { RichTextBridge.setRuns(body.editable, start, after.runs, body.env) }
    }

    /** Puts the original character styles back (no-op when nothing is previewed or the text changed meanwhile). */
    fun restore() {
        val original = before ?: return
        if (intact()) body.batch { RichTextBridge.setRuns(body.editable, start, original.runs, body.env) }
        drop()
    }

    private fun snapshot(): RichText? {
        val sel = body.selection
        if (sel.collapsed) return null
        start = sel.min
        length = body.length
        return body.slice(sel.min, sel.max).also { before = it }
    }

    /** The preview is only valid while the text is as long as when it started (nothing was typed under the finger). */
    private fun intact(): Boolean = body.length == length

    private fun drop() {
        before = null; start = -1; length = -1
    }

    companion object {
        /** The character style a pick applies: the heading's size and bold, Body's default size, or a plain size. */
        fun style(pick: FontSizePick): Pair<Float, Boolean> = when (pick) {
            is FontSizePick.Heading -> pick.heading.size.toFloat() to true
            FontSizePick.Body -> Limits.DEFAULT_FONT_SIZE to false
            is FontSizePick.Size -> pick.pt.toFloat() to false
        }

        /** Convenience for callers that only have a [CharStyle] transform to run. */
        fun transform(pick: FontSizePick): (CharStyle) -> CharStyle {
            val (size, bold) = style(pick)
            return { it.copy(fontSize = size, bold = bold) }
        }
    }
}

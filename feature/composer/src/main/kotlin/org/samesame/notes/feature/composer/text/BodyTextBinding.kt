package org.samesame.notes.feature.composer.text

import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.richtext.BodyText
import org.samesame.notes.core.richtext.edit.TextEditEvent
import org.samesame.notes.core.richtext.edit.TextSelection
import org.samesame.notes.feature.composer.history.BodyTextTarget
import org.samesame.notes.feature.composer.history.TextSel
import org.samesame.notes.feature.composer.session.EditorSession

/**
 * Adapter between WP-F's [BodyText] and WP-H's session: the engine's [TextEditEvent]s become history entries
 * (`onTextEdit`, `onCharStyleChange`, `onParagraphStyleChange`), and history applies undo/redo back through the
 * [BodyTextTarget] methods, which use `BodyText.redo(event)` so nothing is re-emitted.
 *
 * Lives as long as the [body]; bind with `session.bindBody(BodyTextBinding(body, session))`.
 */
class BodyTextBinding(val body: BodyText, private val session: EditorSession) : BodyTextTarget {

    private var lastLength = body.length
    private var lastSelection = body.selection

    init {
        body.addEditListener(::onEvent)
        body.addChangeListener(::onChange)
    }

    private fun onEvent(e: TextEditEvent) {
        when (e) {
            is TextEditEvent.TextEdit -> session.onTextEdit(
                e.start, e.removed, e.inserted, e.selectionBefore.toSel(), e.selectionAfter.toSel(), e.composed,
            )
            is TextEditEvent.CharStyleChange -> session.onCharStyleChange(e.start, e.before, e.after, e.selection.toSel())
            is TextEditEvent.ParagraphStyleChange ->
                session.onParagraphStyleChange(e.firstParagraph, e.before, e.after, e.selection.toSel())
        }
    }

    /** A selection move without a text change is a cursor jump: the next keystroke starts a new undo step. */
    private fun onChange() {
        val sel = body.selection
        if (body.length == lastLength && sel != lastSelection) session.breakTypingBurst()
        lastLength = body.length
        lastSelection = sel
    }

    override fun replaceSlice(start: Int, removedLength: Int, inserted: RichText, selection: TextSel) {
        val removed = body.slice(start, start + removedLength)
        val sel = TextSelection(selection.start, selection.end)
        body.redo(TextEditEvent.TextEdit(start, removed, inserted, sel, sel))
    }

    override fun restoreCharStyles(start: Int, fragment: RichText, selection: TextSel) {
        body.redo(TextEditEvent.CharStyleChange(start, fragment, fragment, TextSelection(selection.start, selection.end)))
    }

    override fun restoreParagraphStyles(firstParagraph: Int, styles: List<ParagraphStyle>, selection: TextSel) {
        body.redo(TextEditEvent.ParagraphStyleChange(firstParagraph, styles, styles, TextSelection(selection.start, selection.end)))
    }

    override fun toRichText(): RichText = body.toRichText()

    private fun TextSelection.toSel() = TextSel(start, end)
}

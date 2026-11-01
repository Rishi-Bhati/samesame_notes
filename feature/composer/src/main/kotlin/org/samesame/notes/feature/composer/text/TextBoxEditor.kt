package org.samesame.notes.feature.composer.text

import android.content.Context
import org.samesame.notes.core.ink.DarkModeInk
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.model.document.ObjectLimits
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.richtext.BodyText
import org.samesame.notes.core.richtext.RichTextColors
import org.samesame.notes.core.richtext.edit.TextEditEvent
import org.samesame.notes.core.richtext.edit.TextSelection
import org.samesame.notes.core.richtext.spans.TextRenderEnv
import org.samesame.notes.core.richtext.ui.RichTextEditorState
import org.samesame.notes.feature.composer.canvas.TextBoxGeometry
import org.samesame.notes.feature.composer.history.BodyTextTarget
import org.samesame.notes.feature.composer.history.CharStyleChange
import org.samesame.notes.feature.composer.history.EditTarget
import org.samesame.notes.feature.composer.history.IndexedElement
import org.samesame.notes.feature.composer.history.ParagraphStyleChange
import org.samesame.notes.feature.composer.history.TextEdit
import org.samesame.notes.feature.composer.history.TextSel
import org.samesame.notes.feature.composer.history.UndoManager
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.model.document.NoteObject
import org.samesame.notes.core.model.document.NoteRecording
import org.samesame.notes.core.model.document.PageSpec

/**
 * The inline editor of one text box: its own text body and rich-text editor state (IME, caret, selection handles) in the
 * box's local space (origin = the box's top-left, page units), plus a private history for the Undo/Redo buttons while
 * the box is edited. Nothing reaches the session until the edit ends: [EditorSession.finishTextBoxEdit] then records
 * the whole editing session as one undo step.
 *
 * [fresh] boxes (just inserted) size themselves to the text; existing boxes only grow (and keep their stored height
 * while the text is shorter).
 */
class TextBoxEditor(
    val pageId: String,
    val id: ElementId,
    val fresh: Boolean,
    initial: RichText,
    /** The stored rect of the box (page space). */
    val rect: RectPu,
    context: Context,
    dark: Boolean,
) {
    val body = BodyText(initial, TextRenderEnv())
    val state = RichTextEditorState(body, TextBoxGeometry(rect.width, TextBoxGeometry.UNBOUNDED_HEIGHT), context)
    private val history = UndoManager()

    init {
        setDark(dark)
        state.showLinks = false
    }

    fun setDark(dark: Boolean) {
        state.colors = if (dark) RichTextColors.Dark else RichTextColors.Light
        body.env.colorTransform = if (dark) { c -> DarkModeInk.invert(c) } else { c -> c }
    }

    /** Height the text needs (page units), read after [RichTextEditorState.revision]. */
    val requiredHeight: Float
        get() = (state.engine.layout.height + 2 * ObjectLimits.TEXT_BOX_PADDING_PU).coerceAtLeast(ObjectLimits.MIN_SIZE_PU)

    /** Height of the box while editing. */
    val height: Float
        get() = if (fresh) requiredHeight else maxOf(rect.height, requiredHeight)

    fun toRichText(): RichText = body.toRichText()

    val canUndo: Boolean get() = history.undoSize > 0
    val canRedo: Boolean get() = history.redoSize > 0

    fun undo() { history.undo(target) }
    fun redo() { history.redo(target) }
    fun breakTypingBurst() = history.breakBurst()

    private val adapter = object : BodyTextTarget {
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
    }

    /** Only the text hooks matter to the commands recorded here. */
    private val target = object : EditTarget {
        override val body: BodyTextTarget get() = adapter
        override fun insertElements(pageId: String, items: List<IndexedElement>) = Unit
        override fun removeElements(pageId: String, ids: Set<ElementId>) = Unit
        override fun setElementOrder(pageId: String, order: List<ElementId>) = Unit
        override fun setPageSpecs(pages: List<PageSpec>) = Unit
        override fun replacePageSpec(spec: PageSpec) = Unit
        override fun detachPage(pageId: String) = Unit
        override fun attachPage(content: PageContent) = Unit
        override fun setInlineObject(id: String, obj: NoteObject?) = Unit
        override fun setRecordings(recordings: List<NoteRecording>) = Unit
    }

    private var lastLength = body.length
    private var lastSelection = body.selection

    init {
        body.addEditListener { e ->
            when (e) {
                is TextEditEvent.TextEdit -> history.record(
                    TextEdit(e.start, e.removed, e.inserted, e.selectionBefore.toSel(), e.selectionAfter.toSel(), e.composed),
                )
                is TextEditEvent.CharStyleChange -> history.record(CharStyleChange(e.start, e.before, e.after, e.selection.toSel()))
                is TextEditEvent.ParagraphStyleChange ->
                    history.record(ParagraphStyleChange(e.firstParagraph, e.before, e.after, e.selection.toSel()))
            }
        }
        body.addChangeListener {
            val sel = body.selection
            if (body.length == lastLength && sel != lastSelection) history.breakBurst()
            lastLength = body.length
            lastSelection = sel
        }
    }

    fun dispose() = state.dispose()

    private fun TextSelection.toSel() = TextSel(start, end)
}

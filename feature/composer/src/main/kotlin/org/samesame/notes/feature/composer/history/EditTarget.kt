package org.samesame.notes.feature.composer.history

import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.model.document.NoteObject
import org.samesame.notes.core.model.document.NoteRecording
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText

/** A selection in body offsets (`start == end` is a caret). Mirrors the text engine's selection without depending on it. */
data class TextSel(val start: Int, val end: Int = start)

/** An element together with its position in the page's z-order list. */
class IndexedElement(val index: Int, val element: PageElement)

/**
 * The body text as history sees it. WP-J binds the real `BodyText` (the Editable) behind this; tests use
 * `InMemoryBodyText`. Implementations must not report the edits they apply here back as new edits, and
 * should restart the IME after each call (doc 4.4).
 *
 * Fragments are "touched slices": their `paragraphs` are the exact styles of every paragraph the range touches.
 */
interface BodyTextTarget {
    /** Replaces `[start, start + removedLength)` with [inserted], then selects [selection]. */
    fun replaceSlice(start: Int, removedLength: Int, inserted: RichText, selection: TextSel)

    /** Restores the character styles of `[start, start + fragment.length)` from [fragment] (text unchanged). */
    fun restoreCharStyles(start: Int, fragment: RichText, selection: TextSel)

    /** Restores the styles of paragraphs `firstParagraph until firstParagraph + styles.size`. */
    fun restoreParagraphStyles(firstParagraph: Int, styles: List<ParagraphStyle>, selection: TextSel)

    /** Current body as RichText (called by the autosaver only when the body is dirty). */
    fun toRichText(): RichText
}

/** What a command can touch. Implemented by `EditorSession`; commands never see anything else. */
interface EditTarget {
    val body: BodyTextTarget

    /** Inserts [items] (ascending by index, indices are final positions). */
    fun insertElements(pageId: String, items: List<IndexedElement>)
    fun removeElements(pageId: String, ids: Set<ElementId>)

    /** Reorders the page's elements to [order] (the same id set). */
    fun setElementOrder(pageId: String, order: List<ElementId>)

    fun setPageSpecs(pages: List<PageSpec>)

    /** Replaces the spec with the same id in the current list. */
    fun replacePageSpec(spec: PageSpec)

    /** Drops a page's layer from memory (page deleted); the command keeps the content for undo. */
    fun detachPage(pageId: String)
    fun attachPage(content: PageContent)

    /** Inline object of the body: null removes it. */
    fun setInlineObject(id: String, obj: NoteObject?)

    /** Replaces the note-level recording list (the "Recorded audio" list). */
    fun setRecordings(recordings: List<NoteRecording>)
}

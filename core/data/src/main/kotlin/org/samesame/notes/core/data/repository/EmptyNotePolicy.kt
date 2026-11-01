package org.samesame.notes.core.data.repository

import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.TemplateRef

/**
 * "Empty" for the note list: nothing the user would miss if the note disappeared. A blank title and body,
 * no inline objects or voice recordings, and no page holding ink, objects or PDF/image-template content.
 * Page decoration (a built-in template from "Style of new notes", a page colour, bookmarks) is not content,
 * so clearing every stroke and character from an existing note makes it empty again.
 *
 * [pageElementCounts] holds the element count of each page of [document], in page order.
 * Imported notes are handled by the caller (their side-channel makes them non-empty).
 */
internal fun isNoteEmpty(document: NoteDocument, pageElementCounts: List<Int>): Boolean {
    if (document.title.isNotBlank()) return false
    if (document.body.plainText().isNotBlank()) return false
    if (document.inlineObjects.isNotEmpty()) return false
    if (document.recordings.isNotEmpty()) return false
    if (document.pages.size != pageElementCounts.size) return false
    return document.pages.withIndex().all { (i, page) ->
        page.pdf == null &&
            page.template !is TemplateRef.Image &&
            (pageElementCounts[i] ?: 0) == 0
    }
}

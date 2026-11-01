package org.samesame.notes.core.document

import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.document.NoteDocument

/**
 * What the editor hands to a save: the full structural document plus only the pages whose
 * content changed (every other page file on disk is left untouched).
 */
class NoteSnapshot(
    val document: NoteDocument,
    val dirtyPages: List<PageContent> = emptyList(),
)

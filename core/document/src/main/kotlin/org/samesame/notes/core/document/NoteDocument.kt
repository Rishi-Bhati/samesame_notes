package org.samesame.notes.core.document

import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.core.model.PageTemplate

// NOTE(WP-D): the v1 on-disk classes moved to `legacy/NoteDocumentV1`. These two types keep their old
// package and shape only as the view-model of the deprecated NoteRepository.openDocument/saveDocument
// adapters (feature/composer uses them until WP-H). They are no longer serialized. Remove with WP-H.

@Deprecated("Use org.samesame.notes.core.model.document.NoteDocument via NoteRepository.openContent")
data class NoteDocument(
    val id: String,
    val pageMode: PageMode,
    val pageRatio: PageRatio = PageRatio.A4Portrait,
    val body: String = "",
    val pages: List<PageSpec>,
)

@Deprecated("Use org.samesame.notes.core.model.document.PageSpec")
data class PageSpec(
    val id: String,
    val template: PageTemplate = PageTemplate.Blank,
    val backgroundColor: Long? = null,
    val isBookmarked: Boolean = false,
)

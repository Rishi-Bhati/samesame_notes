package org.samesame.notes.core.export

import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.document.NoteDocument

/**
 * [source] narrowed to the pages in [pageIds]: the document keeps only those pages, in note order. Page layers and
 * media come from [source] unchanged, so every exporter that reads `document.pages` writes just the subset.
 */
class PageSubsetSource(private val source: ExportSource, private val pageIds: Set<String>) : ExportSource {
    override suspend fun document(): NoteDocument {
        val doc = source.document()
        return doc.copy(pages = doc.pages.filter { it.id in pageIds })
    }

    override suspend fun page(pageId: String): PageContent = source.page(pageId)

    override suspend fun media(path: String): ByteArray? = source.media(path)
}

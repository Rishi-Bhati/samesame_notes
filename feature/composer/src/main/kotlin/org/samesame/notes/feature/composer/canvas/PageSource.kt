package org.samesame.notes.feature.composer.canvas

import androidx.ink.strokes.Stroke
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.document.NoteStyle
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.PageSpec

/** Structure the canvas lays out: everything it needs from a `NoteDocument` except the body text. */
class CanvasDocument(val layout: PageLayout, val style: NoteStyle, val pages: List<PageSpec>)

/**
 * What the canvas needs from the editor session (WP-H's `PageStore`/`DocumentHolder` implement it later;
 * [InMemoryPageSource] is the stand-in). Implementations back [document] and [content] with Compose
 * snapshot state, so reading them while drawing re-draws when they change.
 */
interface PageSource {
    val document: CanvasDocument

    /** Loaded content of a page, or null while loading / not loaded (the canvas draws background + template only). */
    fun content(pageId: String): PageContent?

    /** The pages the canvas wants resident (visible plus a margin). Implementations load these and may evict others. */
    fun onPagesWanted(range: IntRange) {}

    /** A live stroke finished: [stroke] is in the space of page [pageId] and must show up in [content] in the same frame. */
    fun addStroke(pageId: String, stroke: Stroke)

    /** Infinite mode: append one more segment (same ratio, same template as the last). */
    fun appendPage()
}

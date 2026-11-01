package org.samesame.notes.feature.composer.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.ink.strokes.Stroke
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.document.NoteStyle
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.PageSpec

/** A [PageSource] that keeps everything in memory. Used by the debug host and tests; no history, no saving. */
class InMemoryPageSource(
    layout: PageLayout = PageLayout(),
    style: NoteStyle = NoteStyle(),
    pages: List<PageSpec> = listOf(PageSpec(id = "p0")),
) : PageSource {
    private var doc by mutableStateOf(CanvasDocument(layout, style, pages))
    private val contents = mutableStateMapOf<String, PageContent>()
    private var nextId = 1L

    override val document: CanvasDocument get() = doc

    override fun content(pageId: String): PageContent? = contents[pageId]

    override fun addStroke(pageId: String, stroke: Stroke) {
        val old = contents[pageId] ?: PageContent(pageId, emptyList(), 0)
        contents[pageId] = PageContent(pageId, old.elements + InkElement(ElementId(nextId++), stroke), old.version + 1)
    }

    override fun appendPage() {
        val last = doc.pages.lastOrNull()
        val id = "p${doc.pages.size}"
        doc = CanvasDocument(doc.layout, doc.style, doc.pages + (last?.follower(id) ?: PageSpec(id)))
    }

    fun update(layout: PageLayout = doc.layout, style: NoteStyle = doc.style) {
        doc = CanvasDocument(layout, style, doc.pages)
    }

    fun clear() { contents.clear() }
}

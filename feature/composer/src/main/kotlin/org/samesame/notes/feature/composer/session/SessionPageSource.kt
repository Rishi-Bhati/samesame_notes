package org.samesame.notes.feature.composer.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.ink.strokes.Stroke
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.feature.composer.canvas.CanvasDocument
import org.samesame.notes.feature.composer.canvas.PageSource
import java.util.UUID

/**
 * Binds the canvas ([PageSource]) to an [EditorSession]. Reads are observable: a tick of Compose state is bumped
 * synchronously on every page/structure change, so `addStroke` shows up in `content` in the same frame.
 */
class SessionPageSource(private val session: EditorSession) : PageSource {
    /** Bumped by page-layer edits: the draw phase re-reads the layer, the editor does not recompose. */
    private var contentTick by mutableLongStateOf(0L)
    /** Bumped only when the layout, style or page list changes (not on title/text/ink edits). */
    private var structureTick by mutableLongStateOf(0L)
    private var lastLayout = session.holder.current.layout
    private var lastStyle = session.holder.current.style
    private var lastPages = session.holder.current.pages
    private var cachedFor: NoteDocument? = null
    private var cachedDocument: CanvasDocument? = null

    init {
        session.pages.onChanged = { contentTick++ }
        session.holder.onChanged = {
            val d = session.holder.current
            if (d.layout != lastLayout || d.style != lastStyle || d.pages != lastPages) {
                lastLayout = d.layout
                lastStyle = d.style
                lastPages = d.pages
                structureTick++
            }
        }
    }

    override val document: CanvasDocument
        get() {
            structureTick // observe structure only
            val d = session.holder.current
            // Called from the canvas draw phase every frame; the holder keeps one instance until a change.
            if (d !== cachedFor) {
                cachedFor = d
                cachedDocument = CanvasDocument(d.layout, d.style, d.pages)
            }
            return cachedDocument!!
        }

    override fun content(pageId: String): PageContent? {
        contentTick // observe
        return session.pages.content(pageId)
    }

    override fun onPagesWanted(range: IntRange) {
        val pages = session.holder.current.pages
        session.pages.want(range.filter { it in pages.indices }.map { pages[it].id })
    }

    override fun addStroke(pageId: String, stroke: Stroke) {
        session.addStroke(pageId, stroke)
    }

    override fun appendPage() {
        val last = session.holder.current.pages.lastOrNull()
        val id = UUID.randomUUID().toString()
        session.addPage(last?.follower(id) ?: PageSpec(id))
    }
}

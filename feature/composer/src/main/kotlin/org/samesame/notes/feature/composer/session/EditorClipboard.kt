package org.samesame.notes.feature.composer.session

import androidx.compose.ui.geometry.Offset
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.model.document.PageSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Elements on the clipboard, in the space of the page they were copied from ([pageId]). */
class ClipboardContent(
    val elements: List<PageElement>,
    val pageId: String,
    /** Top-left of the selection's bounds in that page's space when copied (the normalisation origin). */
    val origin: Offset,
    val width: Float,
    val height: Float,
)

/** One page on the page clipboard: its spec (bookmark and template kept; the id is new on paste) and its layer. */
class PageClipEntry(val spec: PageSpec, val elements: List<PageElement>)

/**
 * Pages copied from the page sorter, in note order. [media] holds the bytes of every `media/…` file the pages use
 * (pictures, voice notes, templates, PDF backgrounds), keyed by path, so the pages paste into any note.
 */
class PageClip(val pages: List<PageClipEntry>, val media: Map<String, ByteArray>)

/**
 * App-scoped clipboard (doc 4.3): object ink and copied pages. Like the system clipboard it holds one thing: copying
 * objects replaces the pages and copying pages replaces the objects.
 *
 * Objects: paste goes to the original position when it is visible, otherwise to the viewport centre; repeated pastes
 * are offset by [PASTE_STEP_PU]. Cross-app ink clipboard: later.
 */
@Singleton
class EditorClipboard @Inject constructor() {
    var content: ClipboardContent? = null
        private set
    var pages: PageClip? = null
        private set
    private var pasteCount = 0

    val hasContent: Boolean get() = content != null
    val hasPages: Boolean get() = pages != null

    fun set(content: ClipboardContent) {
        this.content = content
        this.pages = null
        pasteCount = 0
    }

    fun setPages(clip: PageClip) {
        pages = clip
        content = null
        pasteCount = 0
    }

    fun clear() {
        content = null
        pages = null
        pasteCount = 0
    }

    /**
     * Translation, in the target page's space, to apply to the clipboard elements for the next paste (advances the
     * paste counter). [originVisible]: the original position is on screen, so paste there (offset per repeat);
     * otherwise centre on [viewportCenter] (in the target page's space).
     */
    fun nextPasteOffset(originVisible: Boolean, viewportCenter: Offset): Offset? {
        val c = content ?: return null
        val step = PASTE_STEP_PU * pasteCount++
        return if (originVisible) Offset(step, step)
        else Offset(viewportCenter.x - (c.origin.x + c.width / 2f) + step, viewportCenter.y - (c.origin.y + c.height / 2f) + step)
    }

    companion object {
        const val PASTE_STEP_PU = 24f
    }
}

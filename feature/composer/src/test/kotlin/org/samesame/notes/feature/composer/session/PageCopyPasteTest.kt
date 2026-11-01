package org.samesame.notes.feature.composer.session

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.box
import org.samesame.notes.core.ink.page.floating
import org.samesame.notes.core.model.document.FloatingObject
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.model.document.TemplateRef
import org.samesame.notes.feature.composer.FakePrefs
import org.samesame.notes.feature.composer.FakeStorage
import org.samesame.notes.feature.composer.fakeElement

@OptIn(ExperimentalCoroutinesApi::class)
class PageCopyPasteTest {
    private val storage = FakeStorage()
    private val bundle = HashMap<String, ByteArray>()
    private var stored = 0

    private fun TestScope.session(
        vararg pageIds: String,
        clipboard: EditorClipboard = EditorClipboard(),
        sink: suspend (ByteArray, String) -> String = { _, ext -> "media/new${++stored}.$ext" },
    ) = EditorSession(
        noteId = "n1",
        initialDocument = NoteDocument(id = "n1", pages = pageIds.map { PageSpec(it) }),
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        pageLoader = storage::load,
        saveSink = storage::save,
        packContainer = storage::pack,
        mediaLoader = { bundle[it] },
        mediaSink = { bytes, ext -> sink(bytes, ext).also { bundle[it] = bytes } },
        prefs = FakePrefs(),
        clipboard = clipboard,
        clock = { testScheduler.currentTime },
    )

    private fun imageObject(id: Long, media: String): ObjectElement {
        val json = FloatingObject(ImageObject("o$id", media, 10f, 10f), RectPu(0f, 0f, 10f, 10f)).toJson()
        return ObjectElement(ElementId(id), json, box(0f, 0f, 10f, 10f))
    }

    @Test fun copyTakesSelectedPagesInNoteOrderWithTheirMedia() = runTest {
        val s = session("p1", "p2", "p3")
        runCurrent()
        s.updatePageSpec("p3") { it.copy(template = TemplateRef.Image("media/t.png"), isBookmarked = true) }
        storage.pageFiles["p1"] = PageContent("p1", listOf(fakeElement(1)), 0)
        storage.pageFiles["p3"] = PageContent("p3", listOf(imageObject(2, "media/img.png")), 0)
        bundle["media/t.png"] = byteArrayOf(1)
        bundle["media/img.png"] = byteArrayOf(2)
        bundle["media/unused.png"] = byteArrayOf(3)

        val clip = s.copyPages(setOf("p3", "p1"))!!

        assertThat(clip.pages.map { it.spec.id }).containsExactly("p1", "p3").inOrder()
        assertThat(clip.pages[1].spec.isBookmarked).isTrue()
        assertThat(clip.pages[1].elements.map { it.id.value }).containsExactly(2L)
        assertThat(clip.media.keys).containsExactly("media/t.png", "media/img.png")
    }

    @Test fun copyOfNoPageOfThisNoteIsNull() = runTest {
        val s = session("p1")
        runCurrent()
        assertThat(s.copyPages(setOf("nope"))).isNull()
    }

    @Test fun pasteInsertsNewPagesAfterTheGivenPageWithFreshIds() = runTest {
        // The session loads the first page when it opens, so its layer is seeded before that.
        storage.pageFiles["p1"] = PageContent("p1", listOf(fakeElement(5)), 0)
        val s = session("p1", "p2")
        runCurrent()
        val clip = s.copyPages(setOf("p1"))!!

        val ids = s.pastePages(0, clip)

        assertThat(ids).hasSize(1)
        assertThat(s.holder.current.pages.map { it.id }).containsExactly("p1", ids[0], "p2").inOrder()
        val pasted = s.content(ids[0])!!.elements
        assertThat(pasted).hasSize(1)
        assertThat(pasted[0].id).isNotEqualTo(ElementId(5L))
    }

    @Test fun pasteBeforeTheFirstPage() = runTest {
        val s = session("p1", "p2")
        runCurrent()
        val clip = s.copyPages(setOf("p2"))!!
        val ids = s.pastePages(-1, clip)
        assertThat(s.holder.current.pages.map { it.id }).containsExactly(ids[0], "p1", "p2").inOrder()
    }

    @Test fun undoRemovesPastedPagesAndRedoPutsThemBack() = runTest {
        storage.pageFiles["p1"] = PageContent("p1", listOf(fakeElement(5)), 0)
        val s = session("p1", "p2")
        runCurrent()
        val clip = s.copyPages(setOf("p1"))!!
        val id = s.pastePages(0, clip).single()

        assertThat(s.undo()).isTrue()
        assertThat(s.holder.current.pages.map { it.id }).containsExactly("p1", "p2").inOrder()
        assertThat(s.content(id)).isNull()

        assertThat(s.redo()).isTrue()
        assertThat(s.holder.current.pages.map { it.id }).containsExactly("p1", id, "p2").inOrder()
        assertThat(s.content(id)!!.elements).hasSize(1)
    }

    @Test fun pastedMediaIsStoredAgainAndTheCopiesPointAtIt() = runTest {
        val s = session("p1", clipboard = EditorClipboard())
        runCurrent()
        val clip = PageClip(
            pages = listOf(PageClipEntry(PageSpec("x", template = TemplateRef.Image("media/t.png")), listOf(imageObject(7, "media/img.png")))),
            media = mapOf("media/t.png" to byteArrayOf(1), "media/img.png" to byteArrayOf(2)),
        )

        val id = s.pastePages(0, clip).single()

        val spec = s.holder.current.pages.first { it.id == id }
        val template = spec.template as TemplateRef.Image
        assertThat(template.media).startsWith("media/new")
        val element = s.content(id)!!.elements.single() as ObjectElement
        val obj = element.floating()!!.obj as ImageObject
        assertThat(obj.media).startsWith("media/new")
        assertThat(obj.media).isNotEqualTo("media/img.png")
        assertThat(bundle[obj.media]).isEqualTo(byteArrayOf(2))
    }

    @Test fun pasteWithoutPagesOnTheClipboardDoesNothing() = runTest {
        val s = session("p1")
        runCurrent()
        assertThat(s.pastePages(0, PageClip(emptyList(), emptyMap()))).isEmpty()
        assertThat(s.holder.current.pages.map { it.id }).containsExactly("p1")
    }

    @Test fun pageClipReplacesObjectsAndObjectsReplacePages() {
        val c = EditorClipboard()
        c.setPages(PageClip(listOf(PageClipEntry(PageSpec("p"), emptyList())), emptyMap()))
        assertThat(c.hasPages).isTrue()
        c.set(ClipboardContent(listOf(fakeElement(1)), "p", Offset.Zero, 10f, 10f))
        assertThat(c.hasPages).isFalse()
        assertThat(c.hasContent).isTrue()
        c.setPages(PageClip(emptyList(), emptyMap()))
        assertThat(c.hasContent).isFalse()
        assertThat(c.hasPages).isTrue()
        c.clear()
        assertThat(c.hasPages).isFalse()
    }
}

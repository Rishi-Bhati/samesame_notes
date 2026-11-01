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
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.floating
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.TextWrap
import org.samesame.notes.feature.composer.FakePrefs
import org.samesame.notes.feature.composer.FakeStorage
import org.samesame.notes.feature.composer.fakeElement

/** A new image goes to the bottom layer; "Send to back" / "Bring to front" are one undo step each. */
@OptIn(ExperimentalCoroutinesApi::class)
class ImageLayeringTest {
    private val storage = FakeStorage()

    private fun TestScope.session() = EditorSession(
        noteId = "n1",
        initialDocument = NoteDocument(id = "n1", pages = listOf(PageSpec("p1"))),
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        pageLoader = storage::load,
        saveSink = storage::save,
        packContainer = storage::pack,
        mediaLoader = { null },
        mediaSink = { _, ext -> "media/img.$ext" },
        prefs = FakePrefs(),
        clipboard = EditorClipboard(),
        clock = { testScheduler.currentTime },
    )

    @Test fun newImageIsBehindAndFirst() = runTest {
        val s = session(); runCurrent()
        s.pages.await("p1")
        s.addElements("p1", listOf(fakeElement(1), fakeElement(2)))
        assertThat(s.insertImage("p1", ByteArray(4), "png", 400f, 300f, Offset(540f, 500f))).isTrue()
        val els = s.content("p1")!!.elements
        assertThat(els).hasSize(3)
        val img = els.first() as ObjectElement
        assertThat(img.floating()!!.wrap).isEqualTo(TextWrap.Behind)
        assertThat(els.drop(1).map { it.id.value }).containsExactly(1L, 2L).inOrder()
    }

    @Test fun toEdgeKeepsRelativeOrder() {
        val a = fakeElement(1); val b = fakeElement(2); val c = fakeElement(3); val d = fakeElement(4)
        val list = listOf(a, b, c, d)
        assertThat(ZOrder.toEdge(list, setOf(b.id, d.id), toBack = true).map { it.id.value }).containsExactly(2L, 4L, 1L, 3L).inOrder()
        assertThat(ZOrder.toEdge(list, setOf(a.id, c.id), toBack = false).map { it.id.value }).containsExactly(2L, 4L, 1L, 3L).inOrder()
    }

    @Test fun sendToBackAndBringToFrontAreOneUndoStepEach() = runTest {
        val s = session(); runCurrent()
        s.pages.await("p1")
        s.insertImage("p1", ByteArray(4), "png", 400f, 300f, Offset(540f, 500f))
        val id = s.content("p1")!!.elements.first().id
        s.addElements("p1", listOf(fakeElement(7)))
        s.select("p1", s.content("p1")!!.elements.filter { it.id == id })
        s.bringSelectionToFront()
        var els = s.content("p1")!!.elements
        assertThat(els.last().id).isEqualTo(id)
        assertThat((els.last() as ObjectElement).floating()!!.wrap).isEqualTo(TextWrap.InFront)
        assertThat(s.undo()).isTrue()
        els = s.content("p1")!!.elements
        assertThat(els.first().id).isEqualTo(id)
        assertThat((els.first() as ObjectElement).floating()!!.wrap).isEqualTo(TextWrap.Behind)
        s.select("p1", els.filter { it.id == id })
        s.bringSelectionToFront(); s.sendSelectionToBack()
        els = s.content("p1")!!.elements
        assertThat(els.first().id).isEqualTo(id)
        assertThat((els.first() as ObjectElement).floating()!!.wrap).isEqualTo(TextWrap.Behind)
    }
}

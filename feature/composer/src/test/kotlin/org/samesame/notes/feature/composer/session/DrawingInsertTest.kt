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
import org.samesame.notes.core.model.document.PaintingObject
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.model.document.SizePu
import org.samesame.notes.feature.composer.FakePrefs
import org.samesame.notes.feature.composer.FakeStorage

/** Insert > Drawing: one undo step, kept inside the page, and the re-edit keeps the object's id, scale and place. */
@OptIn(ExperimentalCoroutinesApi::class)
class DrawingInsertTest {
    private val storage = FakeStorage()
    private val page = SizePu(1080f, 1527f)
    private val written = ArrayList<String>()

    private fun TestScope.session() = EditorSession(
        noteId = "n1",
        initialDocument = NoteDocument(id = "n1", pages = listOf(PageSpec("p1"))),
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        pageLoader = storage::load,
        saveSink = storage::save,
        packContainer = storage::pack,
        mediaLoader = { null },
        mediaSink = { bytes, ext -> "media/${bytes.size}-${written.size}.$ext".also { written += it } },
        prefs = FakePrefs(),
        clipboard = EditorClipboard(),
        clock = { testScheduler.currentTime },
    )

    private fun EditorSession.painting(id: org.samesame.notes.core.ink.page.ElementId) =
        (content("p1")!!.elements.first { it.id == id } as ObjectElement).floating()!!

    @Test fun insertIsOneUndoStepAndSelectsTheObject() = runTest {
        val s = session(); runCurrent()
        s.pages.await("p1")
        val id = s.insertDrawing("p1", ByteArray(3), ByteArray(5), RectPu(100f, 200f, 500f, 700f), Offset(540f, 760f), page)!!
        val f = s.painting(id)
        val p = f.obj as PaintingObject
        assertThat(p.content).isEqualTo(RectPu(100f, 200f, 500f, 700f))
        assertThat(p.media).endsWith(".png")
        assertThat(p.ink).endsWith(".drw")
        assertThat(f.rect).isEqualTo(RectPu(340f, 510f, 740f, 1010f)) // 1:1, centred
        assertThat(s.selection.current.ids).containsExactly(id)
        assertThat(s.undo()).isTrue()
        assertThat(s.content("p1")!!.elements).isEmpty()
        assertThat(s.undo()).isFalse()
        assertThat(s.redo()).isTrue()
        assertThat(s.content("p1")!!.elements.map { it.id }).containsExactly(id)
    }

    @Test fun wideDrawingIsScaledToNinetyPercentOfThePageAndKeptInside() = runTest {
        val s = session(); runCurrent()
        s.pages.await("p1")
        val id = s.insertDrawing("p1", ByteArray(1), ByteArray(1), RectPu(0f, 0f, 1080f, 540f), Offset(0f, 0f), page)!!
        val r = s.painting(id).rect
        assertThat(r.width).isWithin(0.01f).of(972f)
        assertThat(r.height).isWithin(0.01f).of(486f)
        assertThat(r.left).isAtLeast(0f)
        assertThat(r.top).isAtLeast(0f)
    }

    @Test fun reEditKeepsIdAndScaleAndIsOneUndoStep() = runTest {
        val s = session(); runCurrent()
        s.pages.await("p1")
        val id = s.insertDrawing("p1", ByteArray(1), ByteArray(1), RectPu(100f, 100f, 300f, 300f), Offset(540f, 760f), page)!!
        val before = s.painting(id)
        // Same scale (1:1); the sheet region grows 50 to the right and 100 down.
        assertThat(s.replaceDrawing("p1", id, ByteArray(9), ByteArray(9), RectPu(100f, 100f, 350f, 400f))).isTrue()
        val after = s.painting(id)
        assertThat((after.obj as PaintingObject).id).isEqualTo((before.obj as PaintingObject).id)
        assertThat(after.rect.left).isWithin(0.01f).of(before.rect.left)
        assertThat(after.rect.top).isWithin(0.01f).of(before.rect.top)
        assertThat(after.rect.width).isWithin(0.01f).of(250f)
        assertThat(after.rect.height).isWithin(0.01f).of(300f)
        assertThat((after.obj as PaintingObject).media).isNotEqualTo((before.obj as PaintingObject).media)
        assertThat(s.undo()).isTrue()
        assertThat(s.painting(id)).isEqualTo(before)
    }

    @Test fun oneSelectedPaintingOffersTheDrawingMenuRow() = runTest {
        val s = session(); runCurrent()
        s.pages.await("p1")
        s.insertDrawing("p1", ByteArray(1), ByteArray(1), RectPu(0f, 0f, 100f, 100f), Offset(500f, 500f), page)
        assertThat(s.objectMenuState(1080f).showDrawing).isTrue()
        s.clearSelection()
        assertThat(s.objectMenuState(1080f).showDrawing).isFalse()
    }
}

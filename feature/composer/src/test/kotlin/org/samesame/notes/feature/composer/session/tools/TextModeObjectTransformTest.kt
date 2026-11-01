package org.samesame.notes.feature.composer.session.tools

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
import org.samesame.notes.feature.composer.FakePrefs
import org.samesame.notes.feature.composer.FakeStorage
import org.samesame.notes.feature.composer.input.DocPoint
import org.samesame.notes.feature.composer.input.InputToolType
import org.samesame.notes.feature.composer.session.EditorClipboard
import org.samesame.notes.feature.composer.session.EditorSession
import org.samesame.notes.feature.composer.toolbar.EditorMode

/**
 * Item 4: a photo selected in text mode keeps text mode; presses on its frame move it through the selection handler,
 * which (text mode: lasso off) never starts a lasso on a miss.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TextModeObjectTransformTest {
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

    private fun handler(session: EditorSession) = SelectionGestureHandler(
        session,
        PageLocator { PageHit("p1", Offset.Zero) },
    ) { 24f }.also { it.allowLasso = { false } }

    private fun EditorSession.imageRectCenter(): Offset {
        val f = (content("p1")!!.elements.single() as ObjectElement).floating()!!
        return Offset((f.rect.left + f.rect.right) / 2f, (f.rect.top + f.rect.bottom) / 2f)
    }

    @Test fun dragOnTheSelectedPhotoMovesItAndKeepsTextMode() = runTest {
        val s = session(); runCurrent(); s.pages.await("p1")
        s.insertImage("p1", ByteArray(4), "png", 400f, 300f, Offset(540f, 500f))
        s.select("p1", s.content("p1")!!.elements)
        val before = s.imageRectCenter()
        val h = handler(s)
        assertThat(h.grabs(before)).isTrue()

        h.onDown(DocPoint(before), InputToolType.Finger)
        h.onMove(listOf(DocPoint(before + Offset(100f, 50f))))
        h.onUp()

        val after = s.imageRectCenter()
        assertThat(after.x - before.x).isWithin(0.5f).of(100f)
        assertThat(after.y - before.y).isWithin(0.5f).of(50f)
        assertThat(s.selection.current.ids).hasSize(1)
        assertThat(s.chrome.value.toolbar.mode).isEqualTo(EditorMode.Text)
    }

    @Test fun aMissDoesNotStartALassoOrDropTheSelection() = runTest {
        val s = session(); runCurrent(); s.pages.await("p1")
        s.insertImage("p1", ByteArray(4), "png", 400f, 300f, Offset(540f, 500f))
        s.select("p1", s.content("p1")!!.elements)
        val h = handler(s)
        val far = Offset(1000f, 1400f)
        assertThat(h.grabs(far)).isFalse()

        h.onDown(DocPoint(far), InputToolType.Finger)
        h.onMove(listOf(DocPoint(far + Offset(-200f, -200f))))
        h.onUp()

        assertThat(h.overlay.value).isNull()
        assertThat(s.selection.current.ids).hasSize(1)
    }
}

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
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.floating
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.ObjectLimits
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.TextBoxObject
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.feature.composer.FakePrefs
import org.samesame.notes.feature.composer.FakeStorage
import org.samesame.notes.feature.composer.session.EditorSession.TextBoxEditResult

/** Inline text box editing: insert, commit (one undo step), empty-box removal. */
@OptIn(ExperimentalCoroutinesApi::class)
class TextBoxEditTest {
    private val storage = FakeStorage()

    private fun TestScope.session() = EditorSession(
        noteId = "n1",
        initialDocument = NoteDocument(id = "n1", pages = listOf(PageSpec("p1"))),
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        pageLoader = storage::load,
        saveSink = storage::save,
        packContainer = storage::pack,
        prefs = FakePrefs(),
        clipboard = EditorClipboard(),
        clock = { testScheduler.currentTime },
    )

    private fun EditorSession.boxes() = content("p1")!!.elements.filterIsInstance<ObjectElement>()
    private fun EditorSession.box() = boxes().single().floating()!!

    private suspend fun TestScope.fresh(): Pair<EditorSession, org.samesame.notes.core.ink.page.ElementId> {
        storage.pageFiles["p1"] = PageContent("p1", emptyList(), 0)
        val s = session()
        runCurrent()
        return s to s.insertTextBox("p1", Offset(500f, 500f))!!
    }

    @Test fun insertedBoxStartsAtTheTopLeftCorner() = runTest {
        val (s, _) = fresh()
        val r = s.box().rect
        assertThat(r.left).isEqualTo(500f)
        assertThat(r.top).isEqualTo(500f)
        assertThat(r.width).isEqualTo(ObjectLimits.DEFAULT_TEXT_BOX_WIDTH_PU)
    }

    @Test fun insertPositionIsTheMarginCornerOrThePasteSpot() {
        val margin = ObjectLimits.TEXT_BOX_MARGIN_PU
        assertThat(margin).isEqualTo(42f) // 16 dp at 2.625 pu per dp
        assertThat(textBoxInsertTopLeft(null)).isEqualTo(Offset(margin, margin))
        // A paste spot keeps its y; its x is held at least one margin in from the left edge.
        assertThat(textBoxInsertTopLeft(Offset(10f, 300f))).isEqualTo(Offset(margin, 300f))
        assertThat(textBoxInsertTopLeft(Offset(600f, 300f))).isEqualTo(Offset(600f, 300f))
    }

    @Test fun typedTextCommitsAsOneStepAndFitsTheFreshBox() = runTest {
        val (s, id) = fresh()
        val result = s.finishTextBoxEdit("p1", id, RichText.of("hello"), heightPu = 96f, fresh = true)
        assertThat(result).isEqualTo(TextBoxEditResult.Updated)
        val f = s.box()
        assertThat((f.obj as TextBoxObject).text.text).isEqualTo("hello")
        assertThat(f.rect.height).isEqualTo(96f)
        assertThat(f.rect.top).isEqualTo(500f) // the box's top-left is where fresh() put it

        // Insert and text are two steps: undo the text, then the box.
        assertThat(s.undo()).isTrue()
        assertThat((s.box().obj as TextBoxObject).text.length).isEqualTo(0)
        assertThat(s.undo()).isTrue()
        assertThat(s.boxes()).isEmpty()
        assertThat(s.redo()).isTrue()
        assertThat(s.redo()).isTrue()
        assertThat((s.box().obj as TextBoxObject).text.text).isEqualTo("hello")
    }

    @Test fun emptyFreshBoxIsCancelledWithoutHistory() = runTest {
        val (s, id) = fresh()
        val result = s.finishTextBoxEdit("p1", id, RichText(), heightPu = 96f, fresh = true)
        assertThat(result).isEqualTo(TextBoxEditResult.Cancelled)
        assertThat(s.boxes()).isEmpty()
        assertThat(s.undo()).isFalse()
        assertThat(s.redo()).isFalse()
    }

    @Test fun emptiedExistingBoxIsRemovedAsOneUndoableStep() = runTest {
        val (s, id) = fresh()
        s.finishTextBoxEdit("p1", id, RichText.of("keep"), heightPu = 96f, fresh = true)
        val result = s.finishTextBoxEdit("p1", id, RichText(), heightPu = 96f, fresh = false)
        assertThat(result).isEqualTo(TextBoxEditResult.Removed)
        assertThat(s.boxes()).isEmpty()
        assertThat(s.undo()).isTrue()
        assertThat((s.box().obj as TextBoxObject).text.text).isEqualTo("keep")
    }

    @Test fun existingBoxOnlyGrowsAndUnchangedTextRecordsNothing() = runTest {
        val (s, id) = fresh()
        s.finishTextBoxEdit("p1", id, RichText.of("a"), heightPu = 96f, fresh = true)
        // Taller than the stored height: grows; shorter text needs: stays.
        s.finishTextBoxEdit("p1", id, RichText.of("a\nb\nc"), heightPu = 300f, fresh = false)
        assertThat(s.box().rect.height).isEqualTo(300f)
        s.finishTextBoxEdit("p1", id, RichText.of("a\nb"), heightPu = 150f, fresh = false)
        assertThat(s.box().rect.height).isEqualTo(300f)
        assertThat(s.finishTextBoxEdit("p1", id, RichText.of("a\nb"), heightPu = 150f, fresh = false)).isEqualTo(TextBoxEditResult.Unchanged)
    }

    @Test fun finishingAMissingBoxIsANoOp() = runTest {
        val (s, id) = fresh()
        s.deleteElement("p1", id)
        assertThat(s.finishTextBoxEdit("p1", id, RichText.of("x"), heightPu = 96f, fresh = true)).isEqualTo(TextBoxEditResult.Unchanged)
    }
}

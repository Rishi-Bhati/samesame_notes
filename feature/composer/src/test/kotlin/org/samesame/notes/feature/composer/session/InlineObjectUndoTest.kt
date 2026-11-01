package org.samesame.notes.feature.composer.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.floating
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextRun
import org.samesame.notes.feature.composer.FakePrefs
import org.samesame.notes.feature.composer.FakeStorage
import org.samesame.notes.feature.composer.history.TextSel

/**
 * An anchored (inline) image selected like text: Delete removes its character and undo brings it back with its object;
 * Remove anchor (body character out, inline entry dropped, floating object added, one group) is one undo step.
 * Mirrors what the composer's text toolbar rows do through the session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InlineObjectUndoTest {
    private val storage = FakeStorage()
    private val image = ImageObject("img1", "media/a.png", 400f, 300f)
    private val body = RichText(
        "a${OBJECT_CHAR}b",
        listOf(TextRun(1, CharStyle()), TextRun(1, CharStyle(objectId = "img1")), TextRun(1, CharStyle())),
    )

    private fun TestScope.session() = EditorSession(
        noteId = "n1",
        initialDocument = NoteDocument(id = "n1", pages = listOf(PageSpec("p1")), body = body, inlineObjects = listOf(image)),
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        pageLoader = storage::load,
        saveSink = storage::save,
        packContainer = storage::pack,
        prefs = FakePrefs(),
        clock = { testScheduler.currentTime },
    )

    /** What the text toolbar's Delete does: replace the selected object character by nothing. */
    private fun EditorSession.deleteRange(start: Int, end: Int) {
        val (st, rem, ins) = (body as InMemoryBodyText).edit(start, end, "")
        onTextEdit(st, rem, ins, TextSel(start, end), TextSel(start))
    }

    @Test fun deletingAnInlineObjectIsUndoable() = runTest {
        val s = session(); runCurrent()
        s.deleteRange(1, 2)
        assertThat(s.body.toRichText().text).isEqualTo("ab")
        assertThat(AnchorToText.objectOffsets(s.body.toRichText(), 0)).isEmpty()
        // The inline entry is kept, so the restored character still finds its object.
        assertThat(s.holder.inlineObject("img1")).isEqualTo(image)

        assertThat(s.undo()).isTrue()
        val restored = s.body.toRichText()
        assertThat(restored.text).isEqualTo("a${OBJECT_CHAR}b")
        assertThat(AnchorToText.objectOffsets(restored, 0)).containsExactly(1 to "img1")
    }

    @Test fun removeAnchorIsOneUndoStep() = runTest {
        val s = session(); runCurrent()
        s.pages.await("p1")
        s.group {
            s.deleteRange(1, 2)
            s.changeInlineObject("img1", null)
            s.addFloatingObject("p1", AnchorToText.floatingFor(image, 10f, 20f, 200f))
        }
        assertThat(s.holder.inlineObject("img1")).isNull()
        val placed = s.content("p1")!!.elements.single() as ObjectElement
        assertThat(placed.floating()!!.obj).isEqualTo(image)
        assertThat(s.body.toRichText().text).isEqualTo("ab")

        assertThat(s.undo()).isTrue()
        assertThat(s.content("p1")!!.elements).isEmpty()
        assertThat(s.holder.inlineObject("img1")).isEqualTo(image)
        assertThat(AnchorToText.objectOffsets(s.body.toRichText(), 0)).containsExactly(1 to "img1")
        // Redo moves it out again.
        assertThat(s.redo()).isTrue()
        assertThat(s.holder.inlineObject("img1")).isNull()
        assertThat(s.content("p1")!!.elements).hasSize(1)
    }
}

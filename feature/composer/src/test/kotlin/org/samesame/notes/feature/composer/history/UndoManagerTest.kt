package org.samesame.notes.feature.composer.history

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.feature.composer.session.InMemoryBodyText

class UndoManagerTest {
    private var now = 0L
    private val body = InMemoryBodyText()
    private val target = object : EditTarget {
        override val body get() = this@UndoManagerTest.body
        override fun insertElements(pageId: String, items: List<IndexedElement>) = Unit
        override fun removeElements(pageId: String, ids: Set<org.samesame.notes.core.ink.page.ElementId>) = Unit
        override fun setElementOrder(pageId: String, order: List<org.samesame.notes.core.ink.page.ElementId>) = Unit
        override fun setPageSpecs(pages: List<org.samesame.notes.core.model.document.PageSpec>) = Unit
        override fun replacePageSpec(spec: org.samesame.notes.core.model.document.PageSpec) = Unit
        override fun detachPage(pageId: String) = Unit
        override fun attachPage(content: org.samesame.notes.core.ink.page.PageContent) = Unit
        override fun setInlineObject(id: String, obj: org.samesame.notes.core.model.document.NoteObject?) = Unit
        override fun setRecordings(recordings: List<org.samesame.notes.core.model.document.NoteRecording>) = Unit
    }
    private val undo = UndoManager(clock = { now })

    private fun type(s: String, at: Long = now) {
        now = at
        val start = body.text.length
        val (st, rem, ins) = body.edit(start, start, s)
        undo.record(TextEdit(st, rem, ins, TextSel(start), TextSel(start + s.length)))
    }

    @Test fun typingBurstIsOneStep() {
        "hello".forEach { type(it.toString(), now + 100) }
        assertThat(undo.undoSize).isEqualTo(1)
        undo.undo(target)
        assertThat(body.text.text).isEmpty()
        undo.redo(target)
        assertThat(body.text.text).isEqualTo("hello")
    }

    @Test fun pauseBreaksBurst() {
        type("a"); type("b", now + 100)
        type("c", now + 1500)
        assertThat(undo.undoSize).isEqualTo(2)
        undo.undo(target)
        assertThat(body.text.text).isEqualTo("ab")
    }

    @Test fun whitespaceAfterWordBreaksBurst() {
        "hi ".forEach { type(it.toString(), now + 50) }
        "yo".forEach { type(it.toString(), now + 50) }
        assertThat(undo.undoSize).isEqualTo(2)
        undo.undo(target)
        assertThat(body.text.text).isEqualTo("hi ")
    }

    @Test fun cursorJumpBreaksBurst() {
        type("abc")
        now += 50
        val (st, rem, ins) = body.edit(0, 0, "X")
        undo.record(TextEdit(st, rem, ins, TextSel(0), TextSel(1)))
        assertThat(undo.undoSize).isEqualTo(2)
    }

    @Test fun styleChangeAndExplicitBreakEndBurst() {
        type("ab")
        undo.record(CharStyleChange(0, RichText.of("ab"), RichText.of("ab"), TextSel(0, 2)))
        type("c", now + 10)
        assertThat(undo.undoSize).isEqualTo(3)
        undo.breakBurst()
        type("d", now + 10)
        assertThat(undo.undoSize).isEqualTo(4)
    }

    @Test fun pasteIsItsOwnStep() {
        type("ab")
        type("long paste", now + 10)
        assertThat(undo.undoSize).isEqualTo(2)
    }

    @Test fun composedInsertsStillCoalesce() {
        for (s in listOf("he", "hel", "hell")) {
            val start = body.text.text.length
            val (st, rem, ins) = body.edit(start, start, s.takeLast(if (s == "he") 2 else 1))
            undo.record(TextEdit(st, rem, ins, TextSel(start), TextSel(start + ins.length), composed = true))
            now += 10
        }
        assertThat(undo.undoSize).isEqualTo(1)
    }

    @Test fun limitIs200() {
        repeat(250) { undo.record(ObjectChange("o$it", null, null)) }
        assertThat(undo.undoSize).isEqualTo(200)
    }

    @Test fun recordClearsRedo() {
        undo.record(ObjectChange("a", null, null))
        undo.undo(target)
        assertThat(undo.state.value.canRedo).isTrue()
        undo.record(ObjectChange("b", null, null))
        assertThat(undo.state.value.canRedo).isFalse()
    }

    @Test fun groupIsOneStep() {
        undo.group { undo.record(ObjectChange("a", null, null)); undo.record(ObjectChange("b", null, null)) }
        assertThat(undo.undoSize).isEqualTo(1)
    }

    @Test fun evictsPastRetainedStrokeBudget() {
        val m = UndoManager(maxRetainedStrokes = 100)
        repeat(5) { m.record(AddElements("p", List(40) { i -> IndexedElement(i, org.samesame.notes.feature.composer.fakeElement(i.toLong())) })) }
        assertThat(m.undoSize).isEqualTo(2) // 2 x 40 = 80 <= 100
    }
}

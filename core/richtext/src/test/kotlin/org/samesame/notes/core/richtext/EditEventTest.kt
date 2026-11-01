package org.samesame.notes.core.richtext

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.RichTextOps
import org.samesame.notes.core.richtext.edit.EditAccumulator
import org.samesame.notes.core.richtext.edit.TextEditEvent.TextEdit
import org.samesame.notes.core.richtext.edit.TextSelection

/**
 * Edit-event mapping on a pure model "document": changes are applied with RichTextOps and the
 * accumulator/merge results are checked by replaying them (undo/redo must restore exactly).
 */
class EditEventTest {

    private var doc = RichText.of("")
    private val acc = EditAccumulator { s, e -> RichTextOps.slice(doc, s, e) }
    private val events = ArrayList<TextEdit>()

    /** One raw replace, as the Editable would report it. */
    private fun change(start: Int, end: Int, text: String, composing: Boolean = false) {
        acc.before(start, end - start, text.length, TextSelection(start), composing)?.let(::complete)
        doc = RichTextOps.replace(doc, start, end, text)
    }

    private fun flush() = acc.take()?.let(::complete)

    private fun complete(p: EditAccumulator.Pending) {
        events += TextEdit(p.start, p.removed, RichTextOps.slice(doc, p.start, p.start + p.length), p.selectionBefore, TextSelection(p.start + p.length), p.composed)
    }

    /** Applies a TextEdit to a model doc exactly (what BodyText.redo does). */
    private fun apply(d: RichText, start: Int, oldLen: Int, frag: RichText): RichText {
        val replaced = RichTextOps.insertRich(RichTextOps.delete(d, start, start + oldLen), start, RichText(frag.text, frag.runs))
        val first = RichTextOps.paragraphIndexAt(replaced, start)
        val ps = replaced.paragraphs.toMutableList()
        frag.paragraphs.forEachIndexed { i, p -> ps[first + i] = p }
        return RichTextOps.normalise(replaced.copy(paragraphs = ps))
    }

    private fun undo(d: RichText, e: TextEdit) = apply(d, e.start, e.inserted.length, e.removed)
    private fun redo(d: RichText, e: TextEdit) = apply(d, e.start, e.removed.length, e.inserted)

    @Test fun hangulCompositionIsOneEvent() {
        doc = RichText.of("가 ")
        // Gboard Korean: the composing region is replaced on every jamo.
        change(2, 2, "ㅎ", composing = true)
        change(2, 3, "하", composing = true)
        change(2, 3, "한", composing = true)
        change(2, 3, "한ㄱ", composing = true)
        change(2, 4, "한글", composing = true)
        flush()
        assertThat(events).hasSize(1)
        val e = events.single()
        assertThat(e.start).isEqualTo(2)
        assertThat(e.removed.text).isEmpty()
        assertThat(e.inserted.text).isEqualTo("한글")
        assertThat(e.composed).isTrue()
        assertThat(undo(doc, e)).isEqualTo(RichText.of("가 "))
    }

    @Test fun autocorrectReplacingAWordMergesWithItsComposition() {
        doc = RichText.of("I am teh")
        change(5, 8, "teh", composing = true) // setComposingRegion + re-set
        change(5, 8, "the")
        flush()
        val e = events.single()
        assertThat(e.removed.text).isEqualTo("teh")
        assertThat(e.inserted.text).isEqualTo("the")
    }

    @Test fun disjointChangeFlushesPendingEdit() {
        doc = RichText.of("abcdef")
        change(1, 1, "X")
        change(6, 6, "Y")
        flush()
        assertThat(events.map { it.inserted.text }).containsExactly("X", "Y").inOrder()
        var d = doc
        events.reversed().forEach { d = undo(d, it) }
        assertThat(d).isEqualTo(RichText.of("abcdef"))
    }

    @Test fun deletionJoiningParagraphsUndoesExactly() {
        val bullet = ParagraphStyle(list = ListKind.Bullet)
        doc = RichText.of("ab\ncd").copy(paragraphs = listOf(bullet, ParagraphStyle()))
        val before = doc
        change(1, 4, "")
        flush()
        val e = events.single()
        assertThat(doc.paragraphs).containsExactly(bullet)
        assertThat(e.removed.paragraphs).containsExactly(bullet, ParagraphStyle()).inOrder()
        assertThat(undo(doc, e)).isEqualTo(before)
        assertThat(redo(before, e)).isEqualTo(doc)
    }

    @Test fun typingBurstMergesAndUndoesToStart() {
        doc = RichText.of("x")
        val start = doc
        for ((i, c) in "hello".withIndex()) {
            change(1 + i, 1 + i, c.toString())
            flush()
        }
        val merged = events.reduce { a, b -> a.mergeWith(b)!! }
        assertThat(merged.inserted.text).isEqualTo("hello")
        assertThat(undo(doc, merged)).isEqualTo(start)
    }

    @Test fun backspaceIntoAndPastBurstMerges() {
        doc = RichText.of("ab")
        val start = doc
        change(2, 2, "cd"); flush()
        change(3, 4, ""); flush() // backspace within the burst
        change(2, 3, ""); flush() // ... and its first char
        change(1, 2, ""); flush() // ... and past its start
        val merged = events.reduce { a, b -> a.mergeWith(b)!! }
        assertThat(merged.start).isEqualTo(1)
        assertThat(merged.removed.text).isEqualTo("b")
        assertThat(merged.inserted.text).isEmpty()
        assertThat(undo(doc, merged)).isEqualTo(start)
        assertThat(redo(start, merged)).isEqualTo(doc)
    }

    @Test fun enterInListMergesWithTypingAndKeepsParagraphStyles() {
        val bullet = ParagraphStyle(list = ListKind.Bullet)
        doc = RichText.of("a").copy(paragraphs = listOf(bullet))
        val start = doc
        change(1, 1, "\n"); flush()
        change(2, 2, "b"); flush()
        val merged = events[0].mergeWith(events[1])!!
        assertThat(merged.inserted.paragraphs).containsExactly(bullet, bullet).inOrder()
        assertThat(redo(start, merged)).isEqualTo(doc)
        assertThat(undo(doc, merged)).isEqualTo(start)
    }

    @Test fun nonContiguousEditsDoNotMerge() {
        doc = RichText.of("abc")
        change(0, 0, "x"); flush()
        change(4, 4, "y"); flush()
        assertThat(events[0].mergeWith(events[1])).isNull()
    }
}

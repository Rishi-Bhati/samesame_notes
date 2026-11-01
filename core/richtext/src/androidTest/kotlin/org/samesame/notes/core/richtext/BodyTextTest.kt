package org.samesame.notes.core.richtext

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.RichTextOps
import org.samesame.notes.core.model.richtext.TextAlign
import org.samesame.notes.core.model.richtext.TextRun
import org.samesame.notes.core.richtext.edit.TextEditEvent
import org.samesame.notes.core.richtext.spans.TextRenderEnv
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class BodyTextTest {

    private val bold = CharStyle(bold = true)
    private val bullet = ParagraphStyle(list = ListKind.Bullet)
    private val todo = ParagraphStyle(list = ListKind.Checklist)

    private fun sample(): RichText = RichTextOps.normalise(
        RichText(
            text = "Plain bold it\nsecond ${OBJECT_CHAR} line\nthird\n",
            runs = listOf(
                TextRun(6, CharStyle()),
                TextRun(4, bold),
                TextRun(3, CharStyle(italic = true, underline = true, color = 0xFFE53935, fontSize = 20f)),
                TextRun(8, CharStyle(strikethrough = true, highlight = 0x80FFEB3B)),
                TextRun(1, CharStyle(objectId = "img-1")),
                TextRun(12, CharStyle()),
            ),
            paragraphs = listOf(
                ParagraphStyle(align = TextAlign.Center),
                ParagraphStyle(list = ListKind.Numbered, indent = 2),
                todo.copy(checked = true),
                todo, // empty last paragraph keeps its style
            ),
        ),
    )

    @Test fun stylesSurviveRoundTripThroughModel() {
        val rt = sample()
        assertThat(BodyText(rt).toRichText()).isEqualTo(rt)
        // Plain bridge too (no empty styled last paragraph there).
        val noTail = RichTextOps.delete(rt, rt.length - 1, rt.length)
        assertThat(RichTextBridge.toRichText(RichTextBridge.toSpannable(noTail, TextRenderEnv()))).isEqualTo(noTail)
    }

    @Test fun typingInheritsStyleAndTypingStyleApplies() {
        val b = BodyText(RichText.of("ab", bold))
        b.setSelection(1)
        b.commitText("x")
        assertThat(b.toRichText()).isEqualTo(RichText.of("axb", bold))
        b.setSelection(3)
        b.applyCharStyle { it.copy(bold = false, italic = true) }
        b.commitText("yz")
        assertThat(b.toRichText().runs).containsExactly(TextRun(3, bold), TextRun(2, CharStyle(italic = true))).inOrder()
        // Moving the caret drops the pending typing style.
        b.applyCharStyle { it.copy(underline = true) }
        b.setSelection(0)
        b.setSelection(5)
        b.commitText("w")
        assertThat(b.toRichText().runs.last()).isEqualTo(TextRun(3, CharStyle(italic = true)))
    }

    @Test fun listContinuesOnEnterAndEndsOnEmptyItem() {
        val b = BodyText(RichText.of("item").copy(paragraphs = listOf(todo.copy(checked = true))))
        b.setSelection(4)
        b.insertNewline()
        assertThat(b.paragraphStyles).containsExactly(todo.copy(checked = true), todo).inOrder()
        b.commitText("next")
        b.insertNewline()
        b.insertNewline() // empty item → list ends, no new paragraph
        assertThat(b.toRichText().text).isEqualTo("item\nnext\n")
        assertThat(b.paragraphStyles).containsExactly(todo.copy(checked = true), todo, ParagraphStyle()).inOrder()
    }

    @Test fun backspaceAtListStartRemovesMarkerFirst() {
        val b = BodyText(RichText.of("a\nb").copy(paragraphs = listOf(bullet, bullet.copy(indent = 1))))
        b.setSelection(2)
        b.deleteBackward()
        assertThat(b.paragraphStyles).containsExactly(bullet, ParagraphStyle(indent = 1)).inOrder()
        assertThat(b.toRichText().text).isEqualTo("a\nb")
        b.deleteBackward() // now joins
        assertThat(b.toRichText().text).isEqualTo("ab")
        assertThat(b.paragraphStyles).containsExactly(bullet)
    }

    @Test fun indentIsClampedAndNumberingFollows() {
        val b = BodyText(RichText.of("a\nb\nc"))
        b.selectAll()
        b.toggleList(ListKind.Numbered)
        assertThat(b.numbers.toList()).containsExactly(1, 2, 3).inOrder()
        b.setSelection(2)
        repeat(Limits.MAX_INDENT_LEVEL + 2) { b.indent() }
        assertThat(b.paragraphStyles[1].indent).isEqualTo(Limits.MAX_INDENT_LEVEL)
        assertThat(b.numbers.toList()).containsExactly(1, 1, 2).inOrder()
        repeat(Limits.MAX_INDENT_LEVEL + 2) { b.outdent() }
        assertThat(b.paragraphStyles[1].indent).isEqualTo(0)
        b.selectAll()
        b.toggleList(ListKind.Numbered)
        assertThat(b.paragraphStyles.all { it.list == ListKind.None }).isTrue()
    }

    @Test fun pasteRichFragmentIsOneEvent() {
        val b = BodyText(RichText.of("xy").copy(paragraphs = listOf(bullet)))
        val events = ArrayList<TextEditEvent>()
        b.addEditListener { events += it }
        b.setSelection(1)
        b.insertRich(RichText("B\nc", listOf(TextRun(1, bold), TextRun(2, CharStyle())), listOf(ParagraphStyle(), ParagraphStyle())))
        assertThat(b.toRichText().text).isEqualTo("xB\ncy")
        assertThat(b.paragraphStyles).containsExactly(bullet, bullet).inOrder()
        assertThat(events).hasSize(1)
        b.undo(events.single())
        assertThat(b.toRichText()).isEqualTo(RichText.of("xy").copy(paragraphs = listOf(bullet)))
    }

    /** Random user actions; undoing all events must walk back through every snapshot, redo forward. */
    @Test fun undoRedoOfEveryEventRestoresExactState() {
        val rnd = Random(42)
        val b = BodyText(sample())
        val events = ArrayList<TextEditEvent>()
        b.addEditListener { events += it }
        val snapshots = arrayListOf(b.toRichText())
        repeat(300) {
            val n = b.length
            val s = rnd.nextInt(n + 1)
            val e = (s + rnd.nextInt(5)).coerceAtMost(n)
            val before = events.size
            when (rnd.nextInt(10)) {
                0, 1 -> { b.setSelection(s, e); b.commitText(listOf("a", "bc", " ", "한").random(rnd)) }
                2 -> { b.setSelection(s); b.insertNewline() }
                3 -> { b.setSelection(s); b.deleteBackward() }
                4 -> { b.setSelection(s, e); b.applyCharStyle { it.copy(bold = !it.bold, fontSize = listOf(null, 20f).random(rnd)) } }
                5 -> { b.setSelection(s, e); b.toggleList(ListKind.entries.random(rnd)) }
                6 -> { b.setSelection(s, e); if (rnd.nextBoolean()) b.indent() else b.outdent() }
                7 -> { b.setSelection(s, e); b.insertRich(b.slice(0, minOf(n, 7))) }
                8 -> { b.setSelection(s); b.deleteForward() }
                else -> { b.setSelection(s); b.insertObject("obj-$it") }
            }
            // One user action → at most one event (never intermediate states).
            assertThat(events.size - before).isAtMost(1)
            if (events.size > before) snapshots += b.toRichText() else assertThat(b.toRichText()).isEqualTo(snapshots.last())
        }
        for (i in events.indices.reversed()) {
            b.undo(events[i])
            assertThat(b.toRichText()).isEqualTo(snapshots[i])
        }
        for (i in events.indices) {
            b.redo(events[i])
            assertThat(b.toRichText()).isEqualTo(snapshots[i + 1])
        }
    }

    @Test fun typedMarkerTurnsIntoBulletAndBackspaceRestoresIt() {
        val b = BodyText()
        b.commitText("-")
        assertThat(b.toRichText().text).isEqualTo("-")
        b.commitText(" ")
        assertThat(b.toRichText().text).isEqualTo("")
        assertThat(b.paragraphStyles.single().list).isEqualTo(ListKind.Bullet)
        val events = ArrayList<TextEditEvent>()
        b.addEditListener { events += it }
        b.deleteBackward()
        assertThat(b.toRichText().text).isEqualTo("- ")
        assertThat(b.paragraphStyles.single().list).isEqualTo(ListKind.None)
    }

    @Test fun typedNumberAndChecklistMarkersConvert() {
        val b = BodyText()
        b.commitText("1. ")
        b.commitText("first")
        b.insertNewline()
        b.commitText("second")
        assertThat(b.paragraphStyles.map { it.list }).containsExactly(ListKind.Numbered, ListKind.Numbered).inOrder()
        assertThat(b.numbers.toList()).containsExactly(1, 2).inOrder()

        val c = BodyText()
        c.commitText("[x] ")
        assertThat(c.paragraphStyles.single()).isEqualTo(ParagraphStyle(list = ListKind.Checklist, checked = true))
        assertThat(c.toRichText().text).isEqualTo("")
    }

    @Test fun inlineBoldItalicStrikeApplyAndLeavePlainText() {
        val b = BodyText()
        b.commitText("*bold*")
        b.commitText(" ")
        assertThat(b.toRichText().text).isEqualTo("bold ")
        assertThat(b.toRichText().runs).containsExactly(TextRun(4, CharStyle(bold = true)), TextRun(1, CharStyle())).inOrder()

        val i = BodyText()
        i.commitText("_it_ ")
        assertThat(i.toRichText().runs.first().style).isEqualTo(CharStyle(italic = true))

        val s = BodyText()
        s.commitText("~gone~ ")
        assertThat(s.toRichText().runs.first().style).isEqualTo(CharStyle(strikethrough = true))
    }

    @Test fun inlineShortcutsDoNotFireInsideWordsOrArithmetic() {
        val b = BodyText()
        b.commitText("snake_case_ ")
        assertThat(b.toRichText().text).isEqualTo("snake_case_ ")
        assertThat(b.toRichText().runs).isEmpty()
        b.setSelection(b.length)
        b.commitText("2*3*4 ")
        assertThat(b.paragraphStyles.single().list).isEqualTo(ListKind.None)
        assertThat(b.toRichText().text).endsWith("2*3*4 ")
    }

    @Test fun enterOnEmptyIndentedItemOutdentsThenEndsList() {
        val b = BodyText(RichText.of("x").copy(paragraphs = listOf(ParagraphStyle(list = ListKind.Bullet, indent = 1))))
        b.setSelection(0)
        b.insertNewline()
        assertThat(b.paragraphStyles.single().indent).isEqualTo(0)
        assertThat(b.toRichText().text).isEqualTo("x")
        b.insertNewline()
        assertThat(b.paragraphStyles.single().list).isEqualTo(ListKind.None)
    }

    @Test fun enterKeepsLeadingIndentOnPlainParagraphs() {
        val b = BodyText()
        b.commitText("    hello")
        b.insertNewline()
        assertThat(b.toRichText().text).isEqualTo("    hello\n    ")
    }

    @Test fun backspaceAtIndentedListStartOutdentsFirst() {
        val b = BodyText(RichText.of("x").copy(paragraphs = listOf(ParagraphStyle(list = ListKind.Bullet, indent = 2))))
        b.setSelection(0)
        b.deleteBackward()
        assertThat(b.paragraphStyles.single()).isEqualTo(ParagraphStyle(list = ListKind.Bullet, indent = 1))
        b.deleteBackward()
        assertThat(b.paragraphStyles.single()).isEqualTo(ParagraphStyle(list = ListKind.Bullet, indent = 0))
        b.deleteBackward()
        assertThat(b.paragraphStyles.single().list).isEqualTo(ListKind.None)
        assertThat(b.toRichText().text).isEqualTo("x")
    }
}

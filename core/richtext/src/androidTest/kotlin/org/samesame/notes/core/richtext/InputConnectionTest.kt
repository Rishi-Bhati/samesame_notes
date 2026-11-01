package org.samesame.notes.core.richtext

import android.view.KeyEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.richtext.edit.TextEditEvent
import org.samesame.notes.core.richtext.input.InputHost
import org.samesame.notes.core.richtext.input.RichTextInputConnection

/** Drives the InputConnection the way Gboard does (composing regions, batches, Hangul). */
@RunWith(AndroidJUnit4::class)
class InputConnectionTest {

    private lateinit var body: BodyText
    private lateinit var ic: RichTextInputConnection
    private val events = ArrayList<TextEditEvent.TextEdit>()

    private fun setUp(initial: RichText) {
        val instr = InstrumentationRegistry.getInstrumentation()
        instr.runOnMainSync {
            body = BodyText(initial)
            body.addEditListener { if (it is TextEditEvent.TextEdit) events += it }
            val host = object : InputHost {
                override fun cut() = Unit
                override fun copy() = Unit
                override fun paste() = Unit
                override fun onImeChanged() = Unit
            }
            ic = RichTextInputConnection(View(instr.targetContext), body, host)
        }
    }

    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Before fun clear() = events.clear()

    @Test fun hangulCompositionCommitsAsOneEdit() {
        setUp(RichText.of("가 "))
        main {
            body.setSelection(2)
            for (step in listOf("ㅎ", "하", "한", "한ㄱ", "한그", "한글")) {
                ic.setComposingText(step, 1)
                assertThat(events).isEmpty() // composition never yields intermediate events
                assertThat(body.composition).isNotNull()
            }
            ic.finishComposingText()
        }
        assertThat(body.toRichText().text).isEqualTo("가 한글")
        assertThat(body.composition).isNull()
        assertThat(body.selection.start).isEqualTo(4)
        assertThat(events).hasSize(1)
        assertThat(events[0].inserted.text).isEqualTo("한글")
        assertThat(events[0].composed).isTrue()
        main { body.undo(events[0]) }
        assertThat(body.toRichText().text).isEqualTo("가 ")
    }

    @Test fun gboardWordCompositionThenSpaceCommit() {
        setUp(RichText.of(""))
        main {
            ic.beginBatchEdit()
            ic.setComposingText("H", 1)
            ic.endBatchEdit()
            ic.setComposingText("He", 1)
            ic.setComposingText("Hel", 1)
            ic.commitText("Hello", 1)
            ic.commitText(" ", 1)
        }
        assertThat(body.toRichText().text).isEqualTo("Hello ")
        assertThat(events.map { it.inserted.text }).containsExactly("Hello", " ").inOrder()
    }

    @Test fun autocorrectViaComposingRegionKeepsStyle() {
        setUp(RichText.of("teh", CharStyle(bold = true)))
        main {
            body.setSelection(3)
            ic.setComposingRegion(0, 3)
            ic.commitText("the", 1)
        }
        assertThat(body.toRichText()).isEqualTo(RichText.of("the", CharStyle(bold = true)))
        assertThat(events.single().removed.text).isEqualTo("teh")
    }

    @Test fun enterKeyEndsEmptyChecklistItem() {
        val todo = ParagraphStyle(list = ListKind.Checklist)
        setUp(RichText.of("a").copy(paragraphs = listOf(todo)))
        main {
            body.setSelection(1)
            ic.commitText("\n", 1)
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        assertThat(body.toRichText().text).isEqualTo("a\n")
        assertThat(body.paragraphStyles).containsExactly(todo, ParagraphStyle()).inOrder()
    }

    @Test fun imeBackspaceAtListStartRemovesMarker() {
        val bullet = ParagraphStyle(list = ListKind.Bullet)
        setUp(RichText.of("a\nb").copy(paragraphs = listOf(bullet, bullet)))
        main {
            body.setSelection(2)
            ic.deleteSurroundingText(1, 0)
        }
        assertThat(body.paragraphStyles).containsExactly(bullet, ParagraphStyle()).inOrder()
        main { ic.deleteSurroundingText(1, 0) }
        assertThat(body.toRichText().text).isEqualTo("ab")
    }

    @Test fun batchOfCommitsIsOneEdit() {
        setUp(RichText.of(""))
        main {
            ic.beginBatchEdit()
            ic.commitText("a", 1)
            ic.commitText("b", 1)
            ic.endBatchEdit()
        }
        assertThat(events.single().inserted.text).isEqualTo("ab")
    }
}

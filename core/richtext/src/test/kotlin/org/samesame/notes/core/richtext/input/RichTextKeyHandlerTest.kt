package org.samesame.notes.core.richtext.input

import android.view.KeyEvent
import android.view.View
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.richtext.BodyText

/** The shared key handler, fed as a hardware keyboard and as the IME's `sendKeyEvent`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RichTextKeyHandlerTest {

    private class Host(val body: () -> BodyText) : InputHost {
        var undos = 0
        var redos = 0
        val styles = ArrayList<KeyStyle>()
        override fun cut() = Unit
        override fun copy() = Unit
        override fun paste() = Unit
        override fun onImeChanged() = Unit
        override fun undo(): Boolean { undos++; return true }
        override fun redo(): Boolean { redos++; return true }
        override fun toggleStyle(style: KeyStyle): Boolean { styles += style; return true }

        // Lines = paragraphs; the goal "x" is the column.
        override fun lineMove(offset: Int, lines: Int, goalX: Float?): Pair<Int, Float> {
            val text = body().editable.toString()
            val starts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
            val line = starts.indexOfLast { it <= offset }
            val col = goalX ?: (offset - starts[line]).toFloat()
            val target = line + lines
            if (target < 0) return 0 to col
            if (target >= starts.size) return text.length to col
            val end = if (target + 1 < starts.size) starts[target + 1] - 1 else text.length
            return (starts[target] + col.toInt()).coerceAtMost(end) to col
        }
    }

    private lateinit var body: BodyText
    private val host = Host { body }
    private lateinit var keys: RichTextKeyHandler

    private fun setUp(text: String, caret: Int = text.length) {
        body = BodyText(RichText.of(text))
        body.setSelection(caret)
        keys = RichTextKeyHandler(body, host)
    }

    private fun down(code: Int, meta: Int = 0) = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, 0, meta)

    private fun press(code: Int, meta: Int = 0): Boolean = keys.onKey(down(code, meta), fromIme = false)

    private val text get() = body.editable.toString()

    @Test fun enterSplitsTheLine() {
        setUp("hello world", caret = 5)
        assertThat(press(KeyEvent.KEYCODE_ENTER)).isTrue()
        assertThat(text).isEqualTo("hello\n world")
        assertThat(body.selection.start).isEqualTo(6)
    }

    @Test fun backspaceAndDelete() {
        setUp("abcd", caret = 2)
        press(KeyEvent.KEYCODE_DEL)
        assertThat(text).isEqualTo("acd")
        press(KeyEvent.KEYCODE_FORWARD_DEL)
        assertThat(text).isEqualTo("ad")
        assertThat(body.selection.start).isEqualTo(1)
    }

    @Test fun typedLettersWithShift() {
        setUp("")
        press(KeyEvent.KEYCODE_H, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        press(KeyEvent.KEYCODE_I)
        press(KeyEvent.KEYCODE_SPACE)
        press(KeyEvent.KEYCODE_1)
        assertThat(text).isEqualTo("Hi 1")
    }

    @Test fun characterBatchFromAdbText() {
        setUp("")
        val ev = KeyEvent(0L, "über", KeyCharacterMapVirtual, 0)
        assertThat(keys.onKey(ev, fromIme = false)).isTrue()
        assertThat(text).isEqualTo("über")
    }

    @Test fun arrowsMoveAndShiftArrowsSelect() {
        setUp("abcdef", caret = 3)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertThat(body.selection.start).isEqualTo(2)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertThat(body.selection.start).isEqualTo(4)
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        press(KeyEvent.KEYCODE_DPAD_LEFT, shift)
        press(KeyEvent.KEYCODE_DPAD_LEFT, shift)
        assertThat(body.selection.start).isEqualTo(4)
        assertThat(body.selection.end).isEqualTo(2)
        // A plain arrow collapses to that side of the selection.
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertThat(body.selection.start).isEqualTo(4)
        assertThat(body.selection.collapsed).isTrue()
    }

    @Test fun ctrlArrowsMoveByWord() {
        setUp("one two three", caret = 13)
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        press(KeyEvent.KEYCODE_DPAD_LEFT, ctrl)
        assertThat(body.selection.start).isEqualTo(8)
        press(KeyEvent.KEYCODE_DPAD_LEFT, ctrl)
        assertThat(body.selection.start).isEqualTo(4)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, ctrl)
        assertThat(body.selection.start).isEqualTo(7)
    }

    @Test fun upDownKeepTheColumn() {
        setUp("abcdef\nab\nabcdef", caret = 5)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertThat(body.selection.start).isEqualTo(9) // end of "ab"
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertThat(body.selection.start).isEqualTo(15) // column 5 again
        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertThat(body.selection.start).isEqualTo(5)
    }

    @Test fun ctrlShortcutsGoToTheHost() {
        setUp("abc")
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        press(KeyEvent.KEYCODE_Z, ctrl)
        press(KeyEvent.KEYCODE_Y, ctrl)
        press(KeyEvent.KEYCODE_Z, ctrl or shift)
        press(KeyEvent.KEYCODE_B, ctrl)
        assertThat(host.undos).isEqualTo(1)
        assertThat(host.redos).isEqualTo(2)
        assertThat(host.styles).containsExactly(KeyStyle.Bold)
        press(KeyEvent.KEYCODE_A, ctrl)
        assertThat(body.selection.min).isEqualTo(0)
        assertThat(body.selection.max).isEqualTo(3)
        // Ctrl+letter never types the letter.
        assertThat(press(KeyEvent.KEYCODE_Q, ctrl)).isFalse()
        assertThat(text).isEqualTo("abc")
    }

    @Test fun tabIndentsAndUnknownKeysPassThrough() {
        setUp("item")
        assertThat(press(KeyEvent.KEYCODE_TAB)).isTrue()
        assertThat(body.currentParagraphStyle().indent).isEqualTo(1)
        press(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        assertThat(body.currentParagraphStyle().indent).isEqualTo(0)
        assertThat(press(KeyEvent.KEYCODE_BACK)).isFalse()
        assertThat(press(KeyEvent.KEYCODE_VOLUME_UP)).isFalse()
        assertThat(keys.onKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A), fromIme = false)).isFalse()
        assertThat(text).isEqualTo("item")
    }

    /** The IME's sendKeyEvent is applied once and never forwarded to the view (where the hardware path listens). */
    @Test fun imeKeyIsAppliedOnceAndNotForwarded() {
        body = BodyText(RichText.of("ab"))
        body.setSelection(1)
        var viewKeys = 0
        val view = object : View(RuntimeEnvironment.getApplication()) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean { viewKeys++; return super.dispatchKeyEvent(event) }
        }
        val shared = RichTextKeyHandler(body, host)
        val ic = RichTextInputConnection(view, body, host, shared)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_X))
        assertThat(text).isEqualTo("a\nxb")
        assertThat(viewKeys).isEqualTo(0)
    }

    /** IME-committed text is not touched by the key path: a commit is one insert. */
    @Test fun imeCommitIsNotDuplicated() {
        setUp("")
        val ic = RichTextInputConnection(View(RuntimeEnvironment.getApplication()), body, host, keys)
        ic.setComposingText("hel", 1)
        ic.setComposingText("hello", 1)
        ic.commitText("hello", 1)
        ic.commitText("\n", 1)
        assertThat(text).isEqualTo("hello\n")
    }

    /** A hardware letter during an IME composition ends the composition and is typed once after it. */
    @Test fun hardwareLetterEndsComposition() {
        setUp("")
        val ic = RichTextInputConnection(View(RuntimeEnvironment.getApplication()), body, host, keys)
        ic.setComposingText("ab", 1)
        assertThat(body.composition).isNotNull()
        press(KeyEvent.KEYCODE_C)
        assertThat(body.composition).isNull()
        assertThat(text).isEqualTo("abc")
    }

    private companion object {
        const val KeyCharacterMapVirtual = android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
    }
}

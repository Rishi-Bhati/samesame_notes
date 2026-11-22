package org.samesame.notes.core.richtext.input

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.richtext.BodyText
import org.samesame.notes.core.richtext.layout.TextBoxGeometry
import org.samesame.notes.core.richtext.ui.RichTextEditor
import org.samesame.notes.core.richtext.ui.RichTextEditorState

/** Hardware keys sent to the focused editor composable reach the body through [TextInputNode]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HardwareKeyboardEditorTest {

    @get:Rule val rule = createComposeRule()

    private lateinit var body: BodyText
    private lateinit var state: RichTextEditorState
    private var undos = 0

    private fun show(text: String, caret: Int = text.length) {
        body = BodyText(RichText.of(text))
        body.setSelection(caret)
        rule.setContent {
            val context = LocalContext.current
            state = androidx.compose.runtime.remember { RichTextEditorState(body, TextBoxGeometry(600f, TextBoxGeometry.UNBOUNDED_HEIGHT), context) }
            state.onUndo = { undos++; true }
            RichTextEditor(state, Modifier.size(300.dp, 400.dp))
        }
        rule.runOnIdle { state.requestFocus() }
        rule.waitForIdle()
    }

    private val text get() = body.editable.toString()

    @Test fun enterBackspaceDeleteAndLetters() {
        show("hello world", caret = 5)
        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertThat(text).isEqualTo("hello\n world") }
        rule.onRoot().performKeyInput { pressKey(Key.Backspace) }
        rule.runOnIdle { assertThat(text).isEqualTo("hello world") }
        rule.onRoot().performKeyInput { pressKey(Key.Delete) }
        rule.runOnIdle { assertThat(text).isEqualTo("helloworld") }
        rule.onRoot().performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.X) }
            pressKey(Key.Y)
        }
        rule.runOnIdle { assertThat(text).isEqualTo("helloXyworld") }
    }

    @Test fun arrowsShiftSelectionAndUndo() {
        show("abcdef", caret = 3)
        rule.onRoot().performKeyInput {
            pressKey(Key.DirectionLeft)
            withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight); pressKey(Key.DirectionRight) }
        }
        rule.runOnIdle {
            assertThat(body.selection.start).isEqualTo(2)
            assertThat(body.selection.end).isEqualTo(4)
        }
        rule.onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        rule.runOnIdle {
            assertThat(undos).isEqualTo(1)
            assertThat(text).isEqualTo("abcdef")
        }
        rule.onRoot().performKeyInput { pressKey(Key.MoveEnd) }
        rule.runOnIdle { assertThat(body.selection.start).isEqualTo(6) }
    }
}

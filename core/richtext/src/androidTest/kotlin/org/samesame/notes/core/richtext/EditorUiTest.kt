package org.samesame.notes.core.richtext

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.richtext.layout.SimplePageGeometry
import org.samesame.notes.core.richtext.layout.ViewTransform
import org.samesame.notes.core.richtext.ui.RichTextEditor
import org.samesame.notes.core.richtext.ui.RichTextEditorState
import org.samesame.notes.core.richtext.ui.rememberRichTextEditorState

@RunWith(AndroidJUnit4::class)
class EditorUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun tapPlacesCaretAndStartsImeSession() {
        val body = BodyText(RichText.of("Hello world"))
        lateinit var state: RichTextEditorState
        lateinit var view: android.view.View
        rule.setContent {
            view = LocalView.current
            state = rememberRichTextEditorState(body, SimplePageGeometry(1, continuous = true))
            state.transform = ViewTransform(scale = 1f)
            RichTextEditor(state, Modifier.fillMaxSize().testTag("editor"))
        }
        // Tap after "Hello" (x ≈ margin + width of "Hello", first line).
        val caret = rule.runOnIdle { state.engine.caret(5) }
        rule.onNodeWithTag("editor").performTouchInput { click(Offset(caret.x, (caret.top + caret.bottom) / 2f)) }
        rule.waitUntil(5_000) { state.isFocused }
        rule.runOnIdle { assertThat(body.selection.start).isIn(com.google.common.collect.Range.closed(4, 6)) }
        val imm = rule.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        rule.waitUntil(5_000) { rule.runOnIdle { imm.isActive(view) } }
        // Checkbox-free body: no paragraph events from the tap.
        rule.runOnIdle { assertThat(body.toRichText()).isEqualTo(RichText.of("Hello world")) }
    }
}

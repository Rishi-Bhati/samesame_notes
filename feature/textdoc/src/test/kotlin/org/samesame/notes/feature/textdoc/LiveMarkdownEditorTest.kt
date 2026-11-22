package org.samesame.notes.feature.textdoc

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import org.samesame.notes.core.richtext.markdown.taskBoxRect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.samesame.notes.core.designsystem.theme.SameSameTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveMarkdownEditorTest {
    @get:Rule val rule = createComposeRule()

    private val source = "# Title\n\nSome **bold** and `code`.\n- [ ] task\n> quote\n```\nval x = 1\n```\n[link](https://example.com)\n"

    private fun editorText(): String =
        rule.onNodeWithTag("editor").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test fun `live editor shows the exact source and typing keeps it byte for byte`() {
        val edits = mutableListOf<String>()
        rule.setContent {
            SameSameTheme(darkTheme = true) {
                TextDocEditor(
                    TextDocUiState(text = source, isMarkdown = true, markdownMode = MarkdownViewMode.Live, isLoading = false),
                    live = true,
                    onText = { edits += it },
                    modifier = Modifier.testTag("editor"),
                )
            }
        }
        assertThat(editorText()).isEqualTo(source)
        rule.onNodeWithTag("editor").performClick()
        rule.onNodeWithTag("editor").performTextInput("## more **x**")
        rule.waitForIdle()
        assertThat(edits.last()).contains("## more **x**")
        assertThat(edits.last().replace("## more **x**", "")).isEqualTo(source)
    }

    @Test fun `replacing the whole text restyles without crashing`() {
        rule.setContent {
            SameSameTheme(darkTheme = false) {
                TextDocEditor(
                    TextDocUiState(text = source, isMarkdown = true, markdownMode = MarkdownViewMode.Live, isLoading = false),
                    live = true,
                    onText = {},
                    modifier = Modifier.testTag("editor"),
                )
            }
        }
        val big = "```\n" + "line **b** with `c`\n".repeat(5000)
        rule.onNodeWithTag("editor").performTextReplacement(big)
        rule.waitForIdle()
        assertThat(editorText()).isEqualTo(big)
        rule.onNodeWithTag("editor").performTextReplacement("")
        rule.waitForIdle()
        assertThat(editorText()).isEmpty()
    }

    @Test fun `tapping a drawn task box toggles it in the source`() {
        val edits = mutableListOf<String>()
        rule.setContent {
            SameSameTheme(darkTheme = true) {
                TextDocEditor(
                    TextDocUiState(text = source, isMarkdown = true, markdownMode = MarkdownViewMode.Live, isLoading = false),
                    live = true,
                    onText = { edits += it },
                    modifier = Modifier.testTag("editor"),
                )
            }
        }
        rule.waitForIdle()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("editor").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.first()
        val box = source.indexOf("[ ]")
        val rect = taskBoxRect(layout, box, 10f)
        rule.onNodeWithTag("editor").performTouchInput { click(rect.center) }
        rule.waitForIdle()
        assertThat(edits.lastOrNull()).isEqualTo(source.replace("- [ ] task", "- [x] task"))
        assertThat(editorText()).isEqualTo(source.replace("- [ ] task", "- [x] task"))
    }

    @Test fun `source mode is the plain editor`() {
        rule.setContent {
            SameSameTheme(darkTheme = true) {
                TextDocEditor(
                    TextDocUiState(text = source, isMarkdown = true, markdownMode = MarkdownViewMode.Source, isLoading = false),
                    live = false,
                    onText = {},
                    modifier = Modifier.testTag("editor"),
                )
            }
        }
        assertThat(editorText()).isEqualTo(source)
    }
}

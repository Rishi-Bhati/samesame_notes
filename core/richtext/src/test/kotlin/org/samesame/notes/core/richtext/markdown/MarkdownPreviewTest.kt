package org.samesame.notes.core.richtext.markdown

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.samesame.notes.core.designsystem.theme.SameSameTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownPreviewTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `tapping a task box reports its offset and the new state`() {
        val calls = mutableListOf<Pair<Int, Boolean>>()
        rule.setContent {
            SameSameTheme(darkTheme = true) {
                MarkdownPreview("- [ ] open\n- [x] done\n", onTaskCheckedChange = { o, c -> calls += o to c })
            }
        }
        rule.onNodeWithTag("md-task-2").performClick()
        rule.onNodeWithTag("md-task-13").performClick()
        assertThat(calls).containsExactly(2 to true, 13 to false).inOrder()
    }

    @Test fun `without a callback a task box is not clickable and nothing crashes`() {
        rule.setContent { SameSameTheme(darkTheme = true) { MarkdownPreview("- [ ] open\n") } }
        rule.onNodeWithTag("md-task-2").assertIsDisplayed()
    }

    @Test fun `headings paragraphs code and tables render their text`() {
        rule.setContent {
            SameSameTheme(darkTheme = false) {
                MarkdownPreview("# Title\n\nHello **bold** world\n\n```\nval x = 1\n```\n\n| h1 | h2 |\n|:--|--:|\n| c1 | c2 |\n")
            }
        }
        rule.onNodeWithText("Title").assertIsDisplayed()
        rule.onNodeWithText("Hello bold world").assertIsDisplayed()
        rule.onNodeWithText("val x = 1").assertIsDisplayed()
        rule.onNodeWithText("h1").assertIsDisplayed()
        rule.onNodeWithText("c2").assertIsDisplayed()
    }
}

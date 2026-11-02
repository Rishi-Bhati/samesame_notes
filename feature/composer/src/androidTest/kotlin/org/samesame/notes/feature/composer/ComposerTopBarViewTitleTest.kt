package org.samesame.notes.feature.composer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.designsystem.theme.SameSameTheme

/**
 * Issue 1: in the view (reading) state a tap on the title text must switch the note to editing and start editing the
 * title (bar title and expanded header title); empty bar space still opens the metadata header.
 */
@RunWith(AndroidJUnit4::class)
class ComposerTopBarViewTitleTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val title = mutableStateOf("Note")
    private val reading = mutableStateOf(true)
    private var edits = 0

    private fun content() {
        rule.setContent {
            SameSameTheme(darkTheme = true) {
                ComposerTopBar(
                    title = title.value,
                    reading = reading.value,
                    onTitleChange = { title.value = it },
                    onTitleDone = {},
                    onBack = {},
                    onReading = { reading.value = true },
                    onEdit = { edits++; reading.value = false },
                    onInsert = {},
                    onSearch = {},
                    onMore = {},
                    viewState = true,
                )
            }
        }
    }

    @Test fun tappingTheBarTitleInTheViewStateStartsEditingIt() {
        content()
        rule.onNodeWithText("Note").performClick()
        rule.waitForIdle()

        assertEquals(1, edits)
        rule.onNode(hasSetTextAction()).performTextReplacement("Edited")
        rule.waitForIdle()
        assertEquals("Edited", title.value)
    }

    @Test fun tappingThePlaceholderOfAnUntitledNoteStartsEditingIt() {
        title.value = ""
        content()
        val hint = rule.activity.getString(R.string.feature_composer_title_hint)
        rule.onNodeWithText(hint).performClick()
        rule.waitForIdle()

        assertEquals(1, edits)
        rule.onNode(hasSetTextAction()).performTextReplacement("First title")
        rule.waitForIdle()
        assertEquals("First title", title.value)
    }

    @Test fun emptyBarSpaceStillOpensTheMetadataHeader() {
        content()
        val x = with(rule.density) { 160.dp.toPx() }
        val y = with(rule.density) { 20.dp.toPx() }
        rule.onRoot().performTouchInput { click(Offset(x, y)) }
        rule.waitForIdle()

        assertEquals(0, edits)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.feature_composer_collapse_info)).assertExists()
    }

    @Test fun tappingTheExpandedHeaderTitleInTheViewStateStartsEditingIt() {
        content()
        // Empty bar space opens the header (the header title's text box starts at 61 dp, 51 dp tall).
        val emptyX = with(rule.density) { 160.dp.toPx() }
        val barY = with(rule.density) { 20.dp.toPx() }
        rule.onRoot().performTouchInput { click(Offset(emptyX, barY)) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)

        // Bar title + header title both carry the text; the header one is composed second.
        rule.onAllNodesWithText("Note")[1].performClick()
        rule.waitForIdle()

        assertEquals(1, edits)
        rule.onNode(hasSetTextAction()).performTextReplacement("Header title")
        rule.waitForIdle()
        assertEquals("Header title", title.value)
    }
}

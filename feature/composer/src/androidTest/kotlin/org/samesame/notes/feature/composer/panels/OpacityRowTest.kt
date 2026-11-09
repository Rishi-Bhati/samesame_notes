package org.samesame.notes.feature.composer.panels

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bug 9: the opacity slider only responded to the -/+ buttons because its Material3 track composable was empty, so the
 * track measured 0 px wide and the drag-to-value maths had no width to map against. The track now draws the checker pill
 * and therefore has a real size; this drags it both ways and checks the % label follows.
 */
@RunWith(AndroidJUnit4::class)
class OpacityRowTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun draggingTheSliderChangesTheOpacity() {
        var value by mutableIntStateOf(45)
        rule.setContent {
            OpacityRow(value = value, onValue = { value = it }, tint = Color(0xFFB06AFF))
        }

        rule.onNodeWithText("45%").assertIsDisplayed()

        rule.onNodeWithContentDescription("opacity").performTouchInput { swipeRight() }
        rule.waitForIdle()
        val afterRight = value
        assertTrue("drag right must raise the opacity, was $value", afterRight > 45)
        rule.onNodeWithText("$afterRight%").assertIsDisplayed()

        rule.onNodeWithContentDescription("opacity").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val afterLeft = value
        assertTrue("drag left must lower the opacity, was $value", afterLeft < afterRight)
        rule.onNodeWithText("$afterLeft%").assertIsDisplayed()
    }

    @Test fun theButtonsStillStepTheValue() {
        var value by mutableIntStateOf(45)
        rule.setContent {
            OpacityRow(value = value, onValue = { value = it }, tint = Color(0xFFB06AFF))
        }

        rule.onNodeWithContentDescription("Increase size").performClick()
        rule.waitForIdle()
        assertTrue("+ must step up, was $value", value == 46)
        rule.onNodeWithContentDescription("Decrease size").performClick()
        rule.waitForIdle()
        assertTrue("- must step down, was $value", value == 45)
    }
}

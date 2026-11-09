package org.samesame.notes.feature.composer.toolbar

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.feature.composer.R
import org.samesame.notes.feature.composer.panels.FontSizeHoldState
import org.samesame.notes.feature.composer.panels.FontSizeMenu
import org.samesame.notes.feature.composer.panels.FontSizePick
import org.samesame.notes.feature.composer.panels.TextSizeCatalog

/**
 * E3 (rc1 round 5): press-and-hold on the font-size button opens the size popup, the same pointer keeps driving it,
 * and the release applies the row under the finger. A stationary hold leaves the popup open; a glide that starts
 * moving at once (no pause on the button) still works.
 */
@RunWith(AndroidJUnit4::class)
class FontSizeGlideTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var open by mutableStateOf(false)
    private val picks = mutableListOf<FontSizePick>()

    private fun setUp() {
        rule.setContent {
            SameSameTheme {
                val hold = remember { FontSizeHoldState() }
                // Popup at the top, toolbar at the bottom: opening the popup must not move the button.
                Box(Modifier.fillMaxSize()) {
                    if (open) {
                        FontSizeMenu(
                            sizes = TextSizeCatalog.SIZES,
                            selectedSize = 17,
                            selectedHeading = null,
                            hold = hold,
                            onPick = { picks += it; open = false },
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                    TextToolbar(
                        model = TextToolbarModel(),
                        onHandwriting = {}, onTodo = {}, onTextOptions = {}, onFontColor = {}, onHighlight = {},
                        onFontSize = { open = !open },
                        onUndo = {}, onRedo = {},
                        fontSizeHold = hold,
                        onFontSizeHoldOpen = { open = true },
                        onFontSizePick = { picks += it; open = false },
                        onFontSizeCancel = { open = false },
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                }
            }
        }
    }

    private val fontSize get() = rule.activity.getString(R.string.feature_composer_font_size)

    /** Root position of the centre of the node showing [text]. */
    private fun centreOf(text: String): Offset {
        val b = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        return Offset((b.left + b.right) / 2f, (b.top + b.bottom) / 2f)
    }

    private fun holdPastTheLongPressTimeout() {
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    @Test fun tapStillTogglesThePopup() {
        setUp()
        rule.onNodeWithContentDescription(fontSize).performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertTrue(open)
        rule.onNodeWithContentDescription(fontSize).performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertTrue(!open)
    }

    @Test fun stationaryHoldOpensThePopupAndReleaseKeepsItOpen() {
        setUp()
        rule.onNodeWithContentDescription(fontSize).performTouchInput { down(center) }
        holdPastTheLongPressTimeout()
        assertTrue("hold must open the popup", open)
        rule.onNodeWithContentDescription(fontSize).performTouchInput { up() }
        rule.waitForIdle()
        assertTrue("release on the button must leave the popup open", open)
        assertTrue(picks.isEmpty())
    }

    @Test fun holdThenGlideToAHeadingAppliesIt() {
        setUp()
        val button = rule.onNodeWithContentDescription(fontSize)
        button.performTouchInput { down(center) }
        holdPastTheLongPressTimeout()
        val heading = rule.activity.getString(R.string.feature_composer_heading_1)
        val target = centreOf(heading)
        val origin = button.fetchSemanticsNode().boundsInRoot.topLeft
        button.performTouchInput {
            // Glide in steps (the pointer stays down) from the button to the Heading 1 row, then lift.
            val from = center
            val to = target - origin
            for (i in 1..20) moveTo(from + (to - from) * (i / 20f))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf<FontSizePick>(FontSizePick.Heading(TextSizeCatalog.Heading.H1)), picks)
    }

    @Test fun glideThatStartsMovingAtOnceStillOpensAndApplies() {
        setUp()
        val button = rule.onNodeWithContentDescription(fontSize)
        val origin = button.fetchSemanticsNode().boundsInRoot.topLeft
        // Move straight up past the touch slop without pausing: the popup opens.
        button.performTouchInput {
            down(center)
            moveBy(Offset(0f, -viewConfiguration.touchSlop * 3))
        }
        rule.waitForIdle()
        assertTrue("a glide off the button must open the popup", open)
        val target = centreOf(rule.activity.getString(R.string.feature_composer_body))
        button.performTouchInput {
            moveTo(target - origin)
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf<FontSizePick>(FontSizePick.Body), picks)
    }

    @Test fun releaseAwayFromThePopupCancels() {
        setUp()
        val button = rule.onNodeWithContentDescription(fontSize)
        button.performTouchInput { down(center) }
        holdPastTheLongPressTimeout()
        assertTrue(open)
        val popupRow = centreOf(rule.activity.getString(R.string.feature_composer_body))
        val origin = button.fetchSemanticsNode().boundsInRoot.topLeft
        button.performTouchInput {
            moveTo(popupRow - origin)
            // Back out of the popup, onto the toolbar: having been in the popup, this is a change of mind.
            moveTo(center)
            up()
        }
        rule.waitForIdle()
        assertTrue("release outside must close the popup", !open)
        assertTrue(picks.isEmpty())
    }
}

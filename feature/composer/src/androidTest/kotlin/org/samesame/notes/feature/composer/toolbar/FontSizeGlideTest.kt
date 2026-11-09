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
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.feature.composer.R
import androidx.compose.ui.unit.dp
import org.samesame.notes.feature.composer.panels.FontSizeHoldState
import org.samesame.notes.feature.composer.panels.GlideScrollPad
import org.samesame.notes.feature.composer.panels.FontSizeMenu
import org.samesame.notes.feature.composer.panels.FontSizePick
import org.samesame.notes.feature.composer.panels.TextSizeCatalog

/**
 * E3: press-and-hold on the font-size button opens the size popup and the same pointer keeps driving it. The highlight
 * starts on the applied size and moves one row per [GlideScrollPad.STEP_DP] of vertical travel; the release applies it.
 * A stationary hold leaves the popup open; a glide that starts moving at once (no pause on the button) still works.
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

    private fun holdPastTheLongPressTimeout() {
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    /** Vertical travel for [rows] glide steps (finger up = negative), in px. */
    private fun travel(rows: Float): Float = with(rule.density) { (rows * GlideScrollPad.STEP_DP).dp.toPx() }

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

    /** The applied size is 17 (flat row 13); 13 steps up reach Heading 1, and further travel stays clamped on it. */
    @Test fun holdThenGlideUpToTheTopAppliesHeading1() {
        setUp()
        val button = rule.onNodeWithContentDescription(fontSize)
        button.performTouchInput { down(center) }
        holdPastTheLongPressTimeout()
        val dy = travel(-13.3f)
        button.performTouchInput {
            for (i in 1..20) moveTo(center + Offset(0f, dy * i / 20f))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf<FontSizePick>(FontSizePick.Heading(TextSizeCatalog.Heading.H1)), picks)
    }

    @Test fun glideThatStartsMovingAtOnceStillOpensAndApplies() {
        setUp()
        val button = rule.onNodeWithContentDescription(fontSize)
        // Move straight up past the touch slop without pausing: the popup opens.
        button.performTouchInput {
            down(center)
            moveBy(Offset(0f, -viewConfiguration.touchSlop * 1.5f))
        }
        rule.waitForIdle()
        assertTrue("a glide off the button must open the popup", open)
        // 10 steps up from "17" is Body.
        val dy = travel(-10f)
        button.performTouchInput {
            moveTo(center + Offset(0f, dy))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf<FontSizePick>(FontSizePick.Body), picks)
    }

    /** The owner's complaint: the same travel must give the same size, fast (one move) or slow (many moves). */
    @Test fun sameTravelGivesTheSameSizeFastOrSlow() {
        setUp()
        val button = rule.onNodeWithContentDescription(fontSize)
        val dy = travel(-3f) // "17" -> 16 -> 14 -> 12
        for (moves in listOf(1, 40)) {
            button.performTouchInput { down(center) }
            holdPastTheLongPressTimeout()
            button.performTouchInput {
                for (i in 1..moves) moveTo(center + Offset(0f, dy * i / moves))
                up()
            }
            rule.waitForIdle()
        }
        assertEquals(listOf<FontSizePick>(FontSizePick.Size(12), FontSizePick.Size(12)), picks)
    }

    @Test fun glideBackOntoTheToolbarCancels() {
        setUp()
        val button = rule.onNodeWithContentDescription(fontSize)
        button.performTouchInput { down(center) }
        holdPastTheLongPressTimeout()
        assertTrue(open)
        val dy = travel(-3f)
        button.performTouchInput {
            moveTo(center + Offset(0f, dy))
            // Back onto the toolbar: having left the button, this is a change of mind.
            moveTo(center)
            up()
        }
        rule.waitForIdle()
        assertTrue("release back on the toolbar must close the popup", !open)
        assertTrue(picks.isEmpty())
    }
}

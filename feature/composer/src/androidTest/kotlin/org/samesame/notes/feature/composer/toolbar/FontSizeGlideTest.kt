package org.samesame.notes.feature.composer.toolbar

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
 * rel 0.2.0: press-and-hold on the font-size button opens the size popup and the same pointer keeps driving it as a
 * joystick around the popup's vertical centre: nothing moves until the finger reaches the centre, then the highlight
 * travels at a speed set by the distance from it (finger above = up the list), and stops when the finger returns to the
 * centre. The release applies the highlighted row; back over the toolbar cancels; a stationary hold leaves it open.
 *
 * The frame loop runs for the whole glide, so the clock is advanced by hand (autoAdvance off).
 */
@RunWith(AndroidJUnit4::class)
class FontSizeGlideTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var open by mutableStateOf(false)
    private val picks = mutableListOf<FontSizePick>()
    private val hold = FontSizeHoldState()

    // Joystick constants (internal to the panels package), restated in dp.
    private val deadZoneDp = 16f
    private val fullSpeedDp = 16f + 136f

    private fun setUp() {
        rule.setContent {
            SameSameTheme {
                // Popup at the top, toolbar at the bottom: opening the popup must not move the button.
                Box(Modifier.fillMaxSize()) {
                    if (open) {
                        FontSizeMenu(
                            sizes = TextSizeCatalog.SIZES,
                            selectedSize = 17,
                            selectedHeading = null,
                            hold = hold,
                            onPick = { picks += it; open = false },
                            modifier = Modifier.align(Alignment.TopEnd).testTag(MENU),
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
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    private val fontSize get() = rule.activity.getString(R.string.feature_composer_font_size)
    private val button get() = rule.onNodeWithContentDescription(fontSize)

    private fun advance(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun px(dp: Float): Float = with(rule.density) { dp.dp.toPx() }

    /** Vertical offset from the button's centre to the popup's centre (px, negative: the popup is above). */
    private fun toCentre(): Float {
        val menu = rule.onNodeWithTag(MENU).getBoundsInRoot()
        val btn = button.getBoundsInRoot()
        return with(rule.density) { ((menu.top + menu.bottom) / 2 - (btn.top + btn.bottom) / 2).toPx() }
    }

    /** Hold the button past the long-press timeout (the popup opens), then glide up to the popup's centre in steps. */
    private fun holdAndGlideToCentre(): Float {
        button.performTouchInput { down(center) }
        advance(1_000)
        assertTrue("hold must open the popup", open)
        val dy = toCentre()
        for (i in 1..10) {
            button.performTouchInput { moveTo(center + Offset(0f, dy * i / 10f)) }
            advance(32)
        }
        return dy
    }

    private fun moveToCentrePlus(dy: Float, offsetPx: Float) = button.performTouchInput { moveTo(center + Offset(0f, dy + offsetPx)) }

    private fun release() {
        button.performTouchInput { up() }
        advance(100)
    }

    @Test fun tapStillTogglesThePopup() {
        setUp()
        button.performTouchInput { down(center); up() }
        advance(100)
        assertTrue(open)
        button.performTouchInput { down(center); up() }
        advance(100)
        assertTrue(!open)
    }

    @Test fun stationaryHoldOpensThePopupAndReleaseKeepsItOpen() {
        setUp()
        button.performTouchInput { down(center) }
        advance(1_000)
        assertTrue("hold must open the popup", open)
        release()
        assertTrue("release on the button must leave the popup open", open)
        assertTrue(picks.isEmpty())
    }

    /** Gliding up from the button to the centre moves nothing: lifting there applies the applied size again. */
    @Test fun nothingMovesUntilTheCentreAndRestingThereKeepsTheSize() {
        setUp()
        val dy = holdAndGlideToCentre()
        val atCentre = hold.highlighted
        assertTrue("the glide starts on the applied row", atCentre >= 0)
        advance(1_500)
        assertEquals(atCentre, hold.highlighted)
        moveToCentrePlus(dy, px(deadZoneDp / 2))
        advance(1_000)
        assertEquals(atCentre, hold.highlighted)
        release()
        assertEquals(listOf<FontSizePick>(FontSizePick.Size(17)), picks)
    }

    /** Held far above the centre the list runs to Heading 1 and stays there. */
    @Test fun holdingAboveTheCentreScrollsToHeading1() {
        setUp()
        val dy = holdAndGlideToCentre()
        moveToCentrePlus(dy, -px(fullSpeedDp + 20f))
        advance(2_500)
        release()
        assertEquals(listOf<FontSizePick>(FontSizePick.Heading(TextSizeCatalog.Heading.H1)), picks)
    }

    /** Held far below the centre the list runs to the largest size in one gesture. */
    @Test fun holdingBelowTheCentreScrollsToTheLargestSize() {
        setUp()
        val dy = holdAndGlideToCentre()
        moveToCentrePlus(dy, px(fullSpeedDp + 20f))
        advance(2_500)
        release()
        assertEquals(listOf<FontSizePick>(FontSizePick.Size(TextSizeCatalog.SIZES.last())), picks)
    }

    /** Just past the dead zone until the highlight moves once, then back to the centre: exactly one step (17 -> 16). */
    @Test fun aSingleStepIsPrecise() {
        setUp()
        val dy = holdAndGlideToCentre()
        val start = hold.highlighted
        moveToCentrePlus(dy, -px(deadZoneDp + 12f))
        var waited = 0
        while (hold.highlighted == start && waited < 2_000) { advance(16); waited += 16 }
        moveToCentrePlus(dy, 0f)
        advance(600)
        assertEquals(start - 1, hold.highlighted)
        release()
        assertEquals(listOf<FontSizePick>(FontSizePick.Size(16)), picks)
    }

    @Test fun glideBackOntoTheToolbarCancels() {
        setUp()
        val dy = holdAndGlideToCentre()
        moveToCentrePlus(dy, -px(60f))
        advance(300)
        // Back onto the toolbar: having left the button, this is a change of mind.
        button.performTouchInput { moveTo(center) }
        advance(100)
        assertEquals(-1, hold.highlighted)
        release()
        assertTrue("release back on the toolbar must close the popup", !open)
        assertTrue(picks.isEmpty())
    }

    private companion object {
        const val MENU = "font_size_menu"
    }
}

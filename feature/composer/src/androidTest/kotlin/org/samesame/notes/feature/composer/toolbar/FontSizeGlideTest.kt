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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.feature.composer.R
import org.samesame.notes.feature.composer.panels.FontSizeHoldState
import org.samesame.notes.feature.composer.panels.FontSizeMenu
import org.samesame.notes.feature.composer.panels.FontSizePick
import org.samesame.notes.feature.composer.panels.FontSizeRows
import org.samesame.notes.feature.composer.panels.GlideRowHeight
import org.samesame.notes.feature.composer.panels.GlideSizePicker
import org.samesame.notes.feature.composer.panels.GlideTopPad
import org.samesame.notes.feature.composer.panels.TextSizeCatalog

/**
 * rel 0.2.2: press-and-glide on the font-size button is DIRECT POINTING. The popup opens so the current size row sits
 * under the finger and the row physically under the finger is highlighted (GlideLayout / GlidePointer); only the top and
 * bottom edge bands auto-scroll. Release applies the highlighted row in one normal size change; sliding far off the popup
 * sideways cancels; a stationary hold leaves the popup open; a plain tap keeps the dropdown list (parity).
 *
 * These drive the real toolbar gesture and observe [FontSizeHoldState]. The popup's frame loop runs for the whole glide,
 * so the clock is advanced by hand (autoAdvance off). Compile-only; run on a device via the rel022 glide device job.
 */
@RunWith(AndroidJUnit4::class)
class FontSizeGlideTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var open by mutableStateOf(false)
    private val picks = mutableListOf<FontSizePick>()
    private val hold = FontSizeHoldState()

    private fun setUp(sample: Boolean = true) {
        rule.setContent {
            SameSameTheme {
                // Toolbar at the bottom (as in the app); the font-size button therefore starts at the bottom of the popup.
                Box(Modifier.fillMaxSize()) {
                    if (open) {
                        if (hold.gliding) {
                            GlideSizePicker(hold = hold, sample = sample, modifier = Modifier.testTag(GLIDE))
                        } else {
                            FontSizeMenu(
                                sizes = TextSizeCatalog.SIZES,
                                selectedSize = 17,
                                selectedHeading = null,
                                onPick = { picks += it; open = false },
                                modifier = Modifier.align(Alignment.TopEnd).testTag(MENU),
                            )
                        }
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
    private val sampleText get() = rule.activity.getString(R.string.feature_composer_font_size_sample)

    private fun advance(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun px(dp: Float): Float = with(rule.density) { dp.dp.toPx() }
    private val rowPx get() = with(rule.density) { GlideRowHeight.toPx() }

    /** Press and wait out the long-press timeout: the glide popup opens and the current row is highlighted. */
    private fun holdToOpen() {
        button.performTouchInput { down(center) }
        advance(1_000)
        assertTrue("hold must open the popup", open)
        assertTrue("hold must start a glide", hold.gliding)
    }

    /** Move the still-down finger by [dy] px vertically (negative = up) from the button centre. */
    private fun moveBy(dx: Float = 0f, dy: Float = 0f) =
        button.performTouchInput { moveTo(center + Offset(dx, dy)) }

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
        assertFalse(open)
    }

    @Test fun stationaryHoldOpensThePopupAndReleaseKeepsItOpen() {
        setUp()
        holdToOpen()
        release()
        assertTrue("release without moving must leave the popup open", open)
        assertTrue(picks.isEmpty())
    }

    /** The current size row starts highlighted under the finger (the anchor), before any movement. */
    @Test fun currentRowStartsUnderTheFinger() {
        setUp()
        holdToOpen()
        val sizes = TextSizeCatalog.SIZES
        val anchor = FontSizeRows.anchorRow(null, 17, sizes)
        assertEquals(anchor, hold.highlighted)
        assertEquals(FontSizePick.Size(17), hold.highlightedPick)
        val placement = hold.placement!!
        val fingerY = with(rule.density) {
            val b = button.getBoundsInRoot()
            ((b.top + b.bottom) / 2f).toPx()
        }
        val centre = placement.listTop(with(rule.density) { GlideTopPad.toPx() }) + (anchor - placement.scroll + 0.5f) * rowPx
        // When the anchor row fits it is exactly under the finger; when the popup is clamped it is within a row.
        assertTrue("anchor row under the finger", kotlin.math.abs(centre - fingerY) <= rowPx + 2f)
        release()
    }

    /** Glide a couple of rows up: the highlighted row is the one under the finger and release applies just that pick. */
    @Test fun releaseAppliesTheHighlightedRow() {
        setUp()
        holdToOpen()
        moveBy(dy = -2f * rowPx)
        advance(32)
        val target = hold.highlighted
        assertTrue("a row must be highlighted", target >= 0)
        val pick = hold.highlightedPick!!
        release()
        assertEquals(listOf(pick), picks)
        assertFalse("release applies and closes the popup", open)
    }

    /** Sliding far off the popup sideways hides the highlight, restores the preview and cancels on release. */
    @Test fun glidingOffTheSideCancelsWithoutChangingTheSize() {
        setUp()
        holdToOpen()
        moveBy(dy = -2f * rowPx)
        advance(32)
        assertTrue(hold.highlighted >= 0)
        moveBy(dx = -px(500f), dy = -2f * rowPx)
        advance(32)
        assertEquals("no highlight while outside the popup", -1, hold.highlighted)
        release()
        assertTrue("cancel must not apply a pick", picks.isEmpty())
        assertFalse(open)
    }

    /** Resting deep in the bottom edge band auto-scrolls to the largest size, applied on release. */
    @Test fun bottomEdgeBandScrollsToTheLargestSize() {
        setUp()
        holdToOpen()
        moveBy(dy = 3f * rowPx)
        advance(2_500)
        assertEquals(FontSizeRows.SIZE_START + TextSizeCatalog.SIZES.lastIndex, hold.highlighted)
        release()
        assertEquals(listOf<FontSizePick>(FontSizePick.Size(TextSizeCatalog.SIZES.last())), picks)
    }

    /** Resting in the top edge band auto-scrolls back to the first heading. */
    @Test fun topEdgeBandScrollsBackToTheStart() {
        setUp()
        holdToOpen()
        moveBy(dy = -20f * rowPx)
        advance(2_500)
        assertEquals(0, hold.highlighted)
        release()
        assertEquals(listOf<FontSizePick>(FontSizePick.Heading(TextSizeCatalog.Heading.H1)), picks)
    }

    /** At a caret the note cannot show the size, so the sample chip ("Aa" at the highlighted size) is shown. */
    @Test fun sampleChipIsShownDuringAGlide() {
        setUp(sample = true)
        holdToOpen()
        rule.onNodeWithText(sampleText).assertIsDisplayed()
        moveBy(dy = -rowPx)
        advance(32)
        rule.onNodeWithText(sampleText).assertIsDisplayed()
        release()
    }

    private companion object {
        const val MENU = "font_size_menu"
        const val GLIDE = "font_size_glide"
    }
}

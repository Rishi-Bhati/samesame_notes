package org.samesame.notes.feature.composer.canvas

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.ink.brush.Brush
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.ink.brush.PenCatalog
import org.samesame.notes.core.model.PenType

/**
 * Regression test for the "highlighter draws blue, turns yellow on lift" bug: `InProgressStrokes` keeps the `nextBrush`
 * lambda captured by its pointer handler, so the provider must read the current brush from state instead of closing over
 * the brush value. Needs androidx.ink natives, hence instrumented.
 */
@RunWith(AndroidJUnit4::class)
class LiveBrushProviderTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val penBlue = PenCatalog.specFor(PenType.Pen).brush(30, 0xFF335FFF)
    private val highlighterYellow = PenCatalog.highlighterFor(0).brush(50, 0xFFFFB426, alpha = 115)

    @Test fun theLiveProviderFollowsToolAndColourChanges() {
        var brush by mutableStateOf(penBlue)
        var firstProvider: (() -> Brush)? = null
        var latestProvider: (() -> Brush)? = null
        rule.setContent {
            val p = rememberLiveBrushProvider(brush)
            SideEffect {
                if (firstProvider == null) firstProvider = p
                latestProvider = p
            }
        }

        rule.waitForIdle()
        assertEquals(penBlue.colorIntArgb, firstProvider!!().colorIntArgb)

        // Same composition, new brush (tool switch / colour pick): the provider must hand out the new brush.
        brush = highlighterYellow
        rule.waitForIdle()
        val afterSwitch = latestProvider!!
        assertEquals(highlighterYellow.colorIntArgb, afterSwitch().colorIntArgb)
        assertEquals(115, afterSwitch().colorIntArgb ushr 24)

        // The lambda instance stays the same, so the pointer handler that captured it keeps reading the current brush.
        assertSame(firstProvider, latestProvider)
    }
}

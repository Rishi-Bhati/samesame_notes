package org.samesame.notes.feature.textdoc

import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.samesame.notes.core.designsystem.theme.SameSameTheme

/**
 * `~~text~~` must show its line in the live editor also while the keyboard is composing the word (the composing
 * underline used to switch it off), inside a link and together with bold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveStrikeRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** Rows of the editor image holding an inked horizontal run at least 25 px long (strike or underline lines). */
    private fun lineRows(text: String, typed: String? = null, composing: Boolean = false): List<Int> {
        rule.setContent {
            SameSameTheme(darkTheme = false) {
                TextDocEditor(
                    TextDocUiState(text = text, isMarkdown = true, markdownMode = MarkdownViewMode.Live, isLoading = false),
                    live = true,
                    onText = {},
                    modifier = Modifier.testTag("e"),
                )
            }
        }
        if (typed != null) {
            rule.onNodeWithTag("e").performClick()
            rule.waitForIdle()
            rule.runOnUiThread {
                val ic = rule.activity.window.decorView.findFocus().onCreateInputConnection(EditorInfo())!!
                ic.setSelection(text.length, text.length)
                if (composing) ic.setComposingText(typed, 1) else ic.commitText(typed, 1)
            }
        }
        rule.waitForIdle()
        val px = rule.onNodeWithTag("e").captureToImage().toPixelMap()
        val bg = px[px.width - 1, px.height - 1]
        fun inked(x: Int, y: Int): Boolean {
            val c = px[x, y]
            return kotlin.math.abs(c.red - bg.red) + kotlin.math.abs(c.green - bg.green) + kotlin.math.abs(c.blue - bg.blue) > 0.6f
        }
        val rows = mutableListOf<Int>()
        for (y in 0 until px.height) {
            var run = 0
            var best = 0
            for (x in 0 until px.width) {
                if (inked(x, y)) { run++; if (run > best) best = run } else run = 0
            }
            if (best >= 25) rows += y
        }
        return rows
    }

    /** Groups adjacent rows: each group is one drawn line. */
    private fun lines(rows: List<Int>): Int = rows.indices.count { it == 0 || rows[it] != rows[it - 1] + 1 }

    @Test fun `typed strike shows its line`() {
        assertThat(lines(lineRows("ab ", typed = "~~strike~~"))).isEqualTo(1)
    }

    @Test fun `strike stays while the keyboard composes the word`() {
        // The strike line plus the composing underline.
        assertThat(lines(lineRows("ab ", typed = "~~strike~~", composing = true))).isEqualTo(2)
    }

    @Test fun `strike and a link underline both show`() {
        assertThat(lines(lineRows("ab ~~[strikelink](https://x.org)~~ cd"))).isEqualTo(2)
    }

    @Test fun `bold strike shows its line`() {
        assertThat(lines(lineRows("ab **~~strike~~** cd"))).isEqualTo(1)
    }

    @Test fun `plain text has no line`() {
        assertThat(lineRows("ab strike cd")).isEmpty()
    }
}

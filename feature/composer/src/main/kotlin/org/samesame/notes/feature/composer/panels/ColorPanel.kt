package org.samesame.notes.feature.composer.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.samesame.notes.feature.composer.R

/**
 * Font / highlight colour panel (`#29`, `#30`): title, custom-colour dots, close, one swatch strip
 * and four page dots. 307 dp wide by 148 dp tall on the reference phone.
 */
@Composable
internal fun ColorPanel(
    title: String,
    selected: Color?,
    onColor: (Color) -> Unit,
    onCustomColor: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** The font-background variant (#30): one strip led by a "none" cell, no custom-colour glyph, no page dots. */
    background: Boolean = false,
) {
    // #29: panel 308 x 148 dp; header 60 dp (close glyph centre 39.8 dp from the right, 37.7 dp right of the dots glyph),
    // strip top 65 dp, dots centre 119.8 dp, 26 dp below the dots.
    PanelCard(modifier, width = 308.dp) {
        Column(Modifier.height(148.2.dp)) {
            PanelHeader(
                title, onClose,
                modifier = Modifier.padding(top = 4.dp, end = if (background) 0.dp else 4.dp),
                actions = {
                    if (!background) androidx.compose.foundation.layout.Box(
                        Modifier.size(40.dp).androidClickable(onCustomColor),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) { DotsGlyph() }
                },
            )
            Spacer(Modifier.height(3.dp))
            SwatchPager(selected, recent = emptyList(), showRecentPage = false, onColor = onColor, onCustom = onCustomColor,
                palette = if (background) listOf(ComposerColors.highlightStrip) else null)
        }
    }
}

private fun Modifier.androidClickable(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

/** 3x2 multicolour dots icon next to the close button (`#29`). */
@Composable
internal fun DotsGlyph(modifier: Modifier = Modifier) {
    val dots = listOf(
        Color(0xFFFF7A59), Color(0xFFFFC928), Color(0xFFFFC928),
        Color(0xFF6BCB77), Color(0xFF4D9BFF), Color(0xFF8E7CFF),
    )
    Canvas(modifier.size(24.dp)) {
        val r = 2.6.dp.toPx(); val gx = 7.dp.toPx(); val gy = 7.dp.toPx()
        val cx = size.width / 2; val cy = size.height / 2
        dots.forEachIndexed { i, c ->
            val col = i % 3; val row = i / 3
            drawCircle(c, r, Offset(cx + (col - 1) * gx, cy + (row - 0.5f) * gy))
        }
    }
}

@ComposerPreviews
@Composable
private fun FontColourPanelPreview() = PreviewFrame {
    ColorPanel(stringResource(R.string.feature_composer_font_colour), ColorPalettes.colors(1, true)[7], {}, {}, {})
}

@ComposerPreviews
@Composable
private fun HighlightColourPanelPreview() = PreviewFrame {
    ColorPanel(stringResource(R.string.feature_composer_font_background), ComposerColors.highlightStrip[1], {}, {}, {}, background = true)
}

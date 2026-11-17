package org.samesame.notes.feature.composer.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.feature.composer.R
import kotlin.math.roundToInt

/** Glide popup rows: compact but easy to aim at (the tap menu keeps the reference's 44 dp). */
internal val GlideRowHeight = 36.dp
internal val GlideTopPad = 8.dp
internal val GlideBottomPad = 8.dp

/**
 * The popup of a press-and-glide on the font-size button: one scrolling column of all rows (headings, Body, then the
 * sizes), placed by [FontSizeHoldState.placement] so the current row started under the finger, showing as many rows
 * as fit. The row under the finger is highlighted; the rows slide by the fractional scroll while the finger rests in
 * an edge band. Fills its parent (the panel layer) so it can place itself in root coordinates; the popup's horizontal
 * position is the tap menu's (`#31`: right edge 56.4 dp from the screen edge).
 *
 * With [sample] (a caret: no text in the note can show the size) a chip with "Aa" at the highlighted size sits left
 * of the popup beside the highlighted row.
 */
@Composable
internal fun GlideSizePicker(
    hold: FontSizeHoldState,
    sample: Boolean,
    modifier: Modifier = Modifier,
) {
    val placement = hold.placement ?: return
    val accent = SameSameTheme.colors.accent
    val density = LocalDensity.current
    val rowPx = with(density) { GlideRowHeight.toPx() }
    val sizes = hold.sizes
    LaunchedEffect(hold) {
        while (true) withFrameNanos(hold::frame)
    }
    Box(modifier.fillMaxSize()) {
        FontSizeMenuSurface(
            Modifier.align(Alignment.TopEnd).padding(end = 56.4.dp).width(FontSizeMenuWidth)
                // Placed in root coordinates: the popup's top is the placement's, whatever this layer's own offset is.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
                    layout(placeable.width, constraints.maxHeight) {
                        val rootTop = coordinates?.boundsInRoot()?.top ?: 0f
                        placeable.place(0, (placement.top - rootTop).roundToInt())
                    }
                }
                .onGloballyPositioned { hold.popupBounds = it.boundsInRoot() },
            topPad = GlideTopPad,
            bottomPad = GlideBottomPad,
        ) {
            Box(Modifier.height(GlideRowHeight * placement.visible).clipToBounds()) {
                // unbounded: the column holds ALL rows. Under the viewport's fixed height Column would give the rows past the
                // viewport zero height, so any scrolled list (anything but the first rows) drew nothing at all.
                Column(Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true).graphicsLayer { translationY = -hold.scroll * rowPx }) {
                    TextSizeCatalog.Heading.entries.forEach { heading ->
                        GlideRow(hold, heading.ordinal, accent) { held ->
                            Text(stringResource(heading.label()), fontSize = 15.sp, color = rowColor(held, accent))
                        }
                    }
                    GlideRow(hold, FontSizeRows.BODY_INDEX, accent) { held ->
                        Text(stringResource(R.string.feature_composer_body), fontSize = 15.sp, color = rowColor(held, accent))
                    }
                    sizes.forEachIndexed { i, size ->
                        GlideRow(hold, FontSizeRows.SIZE_START + i, accent) { held ->
                            Text(size.toString(), fontSize = 15.sp, color = rowColor(held, accent))
                        }
                    }
                }
            }
        }
        // Always: the highlighted number is also shown beside the popup, away from the finger (which covers the row it is on).
        ValueChip(hold, sample, Modifier.align(Alignment.TopEnd).padding(end = 56.4.dp + FontSizeMenuWidth + 12.dp))
    }
}

@Composable
private fun GlideRow(hold: FontSizeHoldState, index: Int, accent: Color, label: @Composable (held: Boolean) -> Unit) {
    val held = hold.highlighted == index
    MenuRow(selected = false, held = held, height = GlideRowHeight, accent = accent, onClick = {}) { label(held) }
}

/** The highlighted value ("Aa" at the highlighted size at a caret), vertically centred on the highlighted row (clamped to this layer). */
@Composable
private fun ValueChip(hold: FontSizeHoldState, sample: Boolean, modifier: Modifier) {
    val pick = hold.highlightedPick ?: return
    val y = hold.highlightedY
    if (y.isNaN()) return
    val (size, bold) = FontSizePreview.style(pick)
    val value = when (pick) {
        is FontSizePick.Heading -> stringResource(pick.heading.label())
        FontSizePick.Body -> stringResource(R.string.feature_composer_body)
        is FontSizePick.Size -> pick.pt.toString()
    }
    val dark = SameSameTheme.colors.isDark
    Box(
        modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0, minWidth = 0))
            layout(placeable.width, constraints.maxHeight) {
                val rootTop = coordinates?.boundsInRoot()?.top ?: 0f
                val top = (y - rootTop - placeable.height / 2f).roundToInt().coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
                placeable.place(0, top)
            }
        }
            .shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
            .background(if (dark) Color(0xFF171717) else Color(0xFFFCFCFC))
            .then(if (dark) Modifier else Modifier.border(0.5.dp, Color(0xFFC7C7C7), RoundedCornerShape(16.dp)))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (!sample) Text(value, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        else Text(
            stringResource(R.string.feature_composer_font_size_sample),
            fontSize = size.sp,
            fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

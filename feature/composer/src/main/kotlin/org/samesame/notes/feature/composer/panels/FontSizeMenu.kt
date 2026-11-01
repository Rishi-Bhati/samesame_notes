package org.samesame.notes.feature.composer.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.feature.composer.R

private val MenuWidth = 152.dp
private val RowHeight = 44.dp
private val MenuMaxHeight = 428.dp
private val MenuShape = RoundedCornerShape(26.dp)

/**
 * Font-size dropdown (`#31`): heading presets and Body first, then the full range of point sizes (scrolls); the
 * current size / heading is drawn in the accent colour. The rows report their root bounds to [hold] so the
 * font-size button can follow a press-and-slide finger over them.
 */
@Composable
internal fun FontSizeMenu(
    sizes: List<Int>,
    selectedSize: Int,
    selectedHeading: TextSizeCatalog.Heading?,
    hold: FontSizeHoldState,
    onPick: (FontSizePick) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = SameSameTheme.colors.accent
    val selectedRow = selectedHeading?.ordinal
        ?: if (selectedSize == TextSizeCatalog.BODY_SIZE) FontSizeRows.BODY_INDEX else -1
    val listScroll = rememberScrollState()
    DisposableEffect(hold, listScroll) {
        hold.scrollState = listScroll
        onDispose { hold.clear() }
    }
    SideEffect { hold.listCount = sizes.size }
    // The same rounded surface as the other toolbar popups (26 dp corners, light 0.5 dp stroke).
    Column(
        modifier.width(MenuWidth).heightIn(max = MenuMaxHeight).shadow(8.dp, MenuShape).clip(MenuShape)
            .background(if (SameSameTheme.colors.isDark) Color(0xFF171717) else Color(0xFFFCFCFC))
            .then(if (SameSameTheme.colors.isDark) Modifier else Modifier.border(0.5.dp, Color(0xFFC7C7C7), MenuShape))
            .onGloballyPositioned { val b = it.boundsInRoot(); hold.reportPopup(b.left, b.right, b.top, b.bottom) }
            .padding(top = 8.dp, bottom = 14.dp),
    ) {
        TextSizeCatalog.Heading.entries.forEach { heading ->
            MenuRow(
                index = heading.ordinal,
                selected = heading.ordinal == selectedRow,
                hold = hold,
                accent = accent,
                onClick = { onPick(FontSizePick.Heading(heading)) },
            ) { Text(stringResource(heading.label()), fontSize = 15.sp, color = rowColor(heading.ordinal == selectedRow, accent)) }
        }
        MenuRow(
            index = FontSizeRows.BODY_INDEX,
            selected = FontSizeRows.BODY_INDEX == selectedRow,
            hold = hold,
            accent = accent,
            onClick = { onPick(FontSizePick.Body) },
        ) { Text(stringResource(R.string.feature_composer_body), fontSize = 15.sp, color = rowColor(FontSizeRows.BODY_INDEX == selectedRow, accent)) }
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp).height(1.dp).background(accent.copy(alpha = 0.35f)))
        Column(
            Modifier
                .onGloballyPositioned { val b = it.boundsInRoot(); hold.reportList(b.top, b.bottom) }
                .verticalScroll(listScroll),
        ) {
            sizes.forEachIndexed { i, size ->
                val index = FontSizeRows.SIZE_START + i
                val selected = selectedHeading == null && selectedSize == size
                MenuRow(
                    index = index,
                    selected = selected,
                    hold = hold,
                    accent = accent,
                    onClick = { onPick(FontSizePick.Size(size)) },
                ) { Text(size.toString(), fontSize = 15.sp, color = rowColor(selected, accent)) }
            }
        }
    }
}

@Composable
private fun rowColor(selected: Boolean, accent: Color): Color =
    if (selected) accent else MaterialTheme.colorScheme.onSurface

private fun TextSizeCatalog.Heading.label(): Int = when (this) {
    TextSizeCatalog.Heading.H1 -> R.string.feature_composer_heading_1
    TextSizeCatalog.Heading.H2 -> R.string.feature_composer_heading_2
    TextSizeCatalog.Heading.H3 -> R.string.feature_composer_heading_3
}

@Composable
private fun MenuRow(
    index: Int,
    selected: Boolean,
    hold: FontSizeHoldState,
    accent: Color,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
) {
    val held = hold.highlighted == index
    Box(
        Modifier.fillMaxWidth().height(RowHeight)
            .onGloballyPositioned {
                val b = it.boundsInRoot()
                hold.reportRow(RowBounds(index, b.top, b.bottom))
            }
            .then(if (held) Modifier.padding(horizontal = 4.dp) else Modifier)
            .clip(RoundedCornerShape(if (held) 8.dp else 0.dp))
            .background(if (held) accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable { onClick() }
            .padding(start = if (held) 26.dp else 30.dp),
        contentAlignment = Alignment.CenterStart,
    ) { label() }
}

@Composable
private fun headingShortLabel(heading: TextSizeCatalog.Heading?): String? = when (heading) {
    null -> null
    TextSizeCatalog.Heading.H1 -> stringResource(R.string.feature_composer_heading_1_short)
    TextSizeCatalog.Heading.H2 -> stringResource(R.string.feature_composer_heading_2_short)
    TextSizeCatalog.Heading.H3 -> stringResource(R.string.feature_composer_heading_3_short)
}

/** Label in the toolbar button: the heading ("H1"..) or the point size. */
@Composable
internal fun fontSizeButtonLabel(size: Int, heading: TextSizeCatalog.Heading?): String =
    headingShortLabel(heading) ?: size.toString()

@ComposerPreviews
@Composable
private fun FontSizeMenuPreview() = PreviewFrame {
    FontSizeMenu(
        sizes = TextSizeCatalog.SIZES,
        selectedSize = 17,
        selectedHeading = null,
        hold = androidx.compose.runtime.remember { FontSizeHoldState() },
        onPick = {},
    )
}

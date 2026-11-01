package org.samesame.notes.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.TransformOrigin
import org.samesame.notes.core.designsystem.motion.MotionPopup
import org.samesame.notes.core.designsystem.motion.PopupMotion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped

/**
 * Rounded dark popup (One UI style) used for overflow, sort and view menus. No vertical padding
 * (rows are 50.67dp, the popup is exactly rows tall), corner radius 26dp, width wraps the content.
 *
 * Like the platform menu it opens below the anchor and starts at the anchor's start edge; when it
 * would leave the window it is end-aligned to the anchor instead ([offset] x moves it towards the
 * start in that case, y always moves it down).
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    motion: PopupMotion = PopupMotion.Menu,
    content: @Composable () -> Unit,
) {
    // The popup stays composed while its exit animation plays.
    var present by remember { mutableStateOf(expanded) }
    if (expanded && !present) present = true
    if (!present) return
    val density = LocalDensity.current
    // Where the popup landed: the scale pivots on the side it is attached to (end when end-aligned, start when
    // start-aligned, bottom when it flipped above the anchor).
    val placement = remember { floatArrayOf(1f, 0f) }
    val provider = remember(offset, density) {
        val ox = with(density) { offset.x.roundToPx() }
        val oy = with(density) { offset.y.roundToPx() }
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ): IntOffset {
                val toRight = anchorBounds.left + ox
                val toLeft = anchorBounds.right - popupContentSize.width - ox
                val x = listOf(toRight, toLeft).firstOrNull { it >= 0 && it + popupContentSize.width <= windowSize.width }
                    ?: (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val below = anchorBounds.bottom + oy
                val fitsBelow = below + popupContentSize.height <= windowSize.height
                val y = if (fitsBelow) below
                else (anchorBounds.top - oy - popupContentSize.height).coerceAtLeast(0)
                placement[0] = if (x == toRight && toRight != toLeft) 0f else 1f
                placement[1] = if (fitsBelow) 0f else 1f
                return IntOffset(x, y)
            }
        }
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
        MotionPopup(
            visible = expanded,
            motion = motion,
            origin = { TransformOrigin(placement[0], placement[1]) },
            onExited = { present = false },
        ) {
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(26.dp),
                color = SameSameTheme.colors.elevated,
                tonalElevation = 0.dp,
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 112.dp)) { content() }
            }
        }
    }
}

/** A menu row; a [checked] row is drawn in the accent colour, bold, with a trailing check. */
@Composable
fun AppMenuItem(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, checked: Boolean? = null) {
    val colors = SameSameTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 50.67.dp)
            .clickable(onClick = onClick)
            .padding(start = 24.4.dp, end = if (checked != null) 18.1.dp else 24.8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = SameSameText.Menu.capped(),
            color = if (checked == true) colors.accent else colors.textPrimary,
            fontWeight = if (checked == true) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (checked != null) {
            Spacer(Modifier.width(17.dp))
            if (checked) Icon(AppIcons.Check, null, tint = colors.accent) else Spacer(Modifier.width(24.dp))
        }
    }
}

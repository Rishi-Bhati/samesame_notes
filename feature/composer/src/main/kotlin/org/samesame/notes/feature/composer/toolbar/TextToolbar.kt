package org.samesame.notes.feature.composer.toolbar

import org.samesame.notes.core.designsystem.icon.AppIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.boundsInRoot
import org.samesame.notes.feature.composer.panels.GlideScrollPad
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.feature.composer.R
import org.samesame.notes.feature.composer.panels.ComposerPreviews
import org.samesame.notes.feature.composer.panels.DarkToolbarColors
import org.samesame.notes.feature.composer.panels.FontSizeHoldGesture
import org.samesame.notes.feature.composer.panels.FontSizeHoldState
import org.samesame.notes.feature.composer.panels.FontSizePick
import org.samesame.notes.feature.composer.panels.FontSizeRows
import org.samesame.notes.feature.composer.panels.TextSizeCatalog
import org.samesame.notes.feature.composer.panels.LightToolbarColors
import org.samesame.notes.feature.composer.panels.ToolbarColors
import org.samesame.notes.feature.composer.panels.PreviewFrame

data class TextToolbarModel(
    val fontSize: Int = 17,
    /** "17" or the heading label ("H1"..). */
    val fontSizeLabel: String = fontSize.toString(),
    val textColor: Color = Color.Unspecified,
    val highlightColor: Color = Color.Unspecified,
    val canUndo: Boolean = true,
    val canRedo: Boolean = false,
    /** Button whose panel is open, shown with a pill behind it. */
    val openPanel: TextToolbarButton? = null,
)

enum class TextToolbarButton { TextOptions, FontColor, Highlight, FontSize }

/**
 * Whether [button] shows the pressed pill: its panel is open, except the text-options button, which stays plain while its
 * panel is open (round-3 reference #28).
 */
fun TextToolbarModel.isHighlighted(button: TextToolbarButton): Boolean =
    openPanel == button && button != TextToolbarButton.TextOptions

/** Height of both toolbars: 42 dp including the 1 px borders (#26: borders at y 2167 and 2276 of 2340 px, 110 px). */
internal val ToolbarHeight = 42.dp

/** Handwriting toggle circle: 38 dp, left edge 7.2 dp from the screen edge (#26: x 19..118 px). */
internal val ToggleSize = 38.dp
internal val ToggleStart = 7.2.dp

/** First cell starts 54.3 dp from the left in both toolbars (#26: centre 206.5 px less half a cell). */
private val TextCellsStart = 54.3.dp

/** Text toolbar cells are 128 px = 48.76 dp apart (#26: centres 206.5, 334, 462.5, 590, 718.5, 849, 977 px). */
private val TextCellWidth = 48.76.dp

@Composable
internal fun toolbarColors(): ToolbarColors = if (SameSameTheme.colors.isDark) DarkToolbarColors else LightToolbarColors

/** The docked toolbar strip: [ToolbarHeight] tall, filled, with a 1 px border on top and bottom. */
@Composable
internal fun ToolbarSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = toolbarColors()
    Box(
        modifier.fillMaxWidth().height(ToolbarHeight).background(c.background).drawBehind {
            drawRect(c.border, Offset.Zero, Size(size.width, 1f))
            drawRect(c.border, Offset(0f, size.height - 1f), Size(size.width, 1f))
        },
    ) { content() }
}

/** The round pen/keyboard toggle that opens both toolbars (#26, #32): 38 dp, fill #2A2A2A, 1 px stroke #5C5C5C. */
@Composable
internal fun ModeToggle(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val c = toolbarColors()
    Box(
        Modifier.size(ToggleSize).clip(CircleShape).background(c.toggleFill).border(0.38.dp, c.toggleStroke, CircleShape)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { icon() }
}

/**
 * Text toolbar (`#26`, `#27`): handwriting toggle, then 48.76 dp cells for to-do, text options, font colour,
 * highlight, size dropdown, undo, redo.
 */
@Composable
fun TextToolbar(
    model: TextToolbarModel,
    onHandwriting: () -> Unit,
    onTodo: () -> Unit,
    onTextOptions: () -> Unit,
    onFontColor: () -> Unit,
    onHighlight: () -> Unit,
    onFontSize: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    fontSizeHold: FontSizeHoldState = androidx.compose.runtime.remember { FontSizeHoldState() },
    onFontSizeHoldOpen: () -> Unit = onFontSize,
    onFontSizePick: (FontSizePick) -> Unit = {},
    onFontSizeCancel: () -> Unit = onFontSize,
    modifier: Modifier = Modifier,
) {
    val c = toolbarColors()
    val haptic = LocalHapticFeedback.current
    val fontSizeCoords = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<LayoutCoordinates?>(null) }
    val toolbarCoords = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<LayoutCoordinates?>(null) }
    // The gesture survives recompositions (the popup opening recomposes this toolbar), so read the latest callbacks.
    val tapFontSize = androidx.compose.runtime.rememberUpdatedState(onFontSize)
    val holdOpen = androidx.compose.runtime.rememberUpdatedState(onFontSizeHoldOpen)
    val pickFontSize = androidx.compose.runtime.rememberUpdatedState(onFontSizePick)
    val cancelFontSize = androidx.compose.runtime.rememberUpdatedState(onFontSizeCancel)
    val currentFontSize = androidx.compose.runtime.rememberUpdatedState(model.fontSize)
    val fontSizeName = stringResource(R.string.feature_composer_font_size)
    ToolbarSurface(modifier) {
        Row(Modifier.fillMaxSize().onGloballyPositioned { toolbarCoords.value = it }, verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(ToggleStart))
            ModeToggle(stringResource(R.string.feature_composer_handwriting), onHandwriting) {
                Icon(AppIcons.Handwriting, null, Modifier.size(24.dp), tint = c.icon)
            }
            Spacer(Modifier.width(TextCellsStart - ToggleStart - ToggleSize))
            Cell(Modifier.width(TextCellWidth)) { ToolButton(AppIcons.Checklist, R.string.feature_composer_todo, onClick = onTodo) }
            Cell(Modifier.width(TextCellWidth)) {
                ToolButton(AppIcons.TextStyle, R.string.feature_composer_text_options, active = model.isHighlighted(TextToolbarButton.TextOptions), onClick = onTextOptions)
            }
            Cell(Modifier.width(TextCellWidth)) {
                ToolButton(AppIcons.TextColor, R.string.feature_composer_font_colour, bar = ColorBar.Text(model.textColor),
                    active = model.isHighlighted(TextToolbarButton.FontColor), onClick = onFontColor)
            }
            Cell(Modifier.width(TextCellWidth)) {
                ToolButton(AppIcons.TextHighlight, R.string.feature_composer_highlight_colour, bar = ColorBar.Highlight(model.highlightColor),
                    active = model.isHighlighted(TextToolbarButton.Highlight), onClick = onHighlight)
            }
            Cell(Modifier.width(TextCellWidth)) {
                // "17" is composer_rich_text_menu_font_size 17sp; the triangle is 21 x 13 px, 9 px after the digits.
                // Tap opens/closes the popup; press-and-hold opens it and follows the finger over its rows (item 11).
                Row(
                    Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
                        .onGloballyPositioned { fontSizeCoords.value = it }
                        .pointerInput(fontSizeHold) {
                            val longPressMs = viewConfiguration.longPressTimeoutMillis
                            val stepPx = GlideScrollPad.STEP_DP.dp.toPx()
                            coroutineScope {
                                // A light tick per step of the glide's highlight (not when it appears or hides).
                                launch {
                                    var last = -1
                                    snapshotFlow { fontSizeHold.highlighted }.collect { h ->
                                        if (h >= 0 && last >= 0 && h != last) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        last = h
                                    }
                                }
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    // Start phase, with our own loop: waitForUpOrCancellation() gives up as soon as the
                                    // finger leaves the 32 dp button, so a glide that starts moving at once never got here.
                                    // Lift = tap; long-press timeout or a move past the touch slop = glide.
                                    val slop = viewConfiguration.touchSlop
                                    val pressedAt = android.os.SystemClock.uptimeMillis()
                                    var start: FontSizeHoldGesture.Start? = null
                                    while (start == null) {
                                        // The timeout runs from the press, not from the last move event.
                                        val left = longPressMs - (android.os.SystemClock.uptimeMillis() - pressedAt)
                                        val event = if (left > 0) withTimeoutOrNull(left) { awaitPointerEvent() } else null
                                        if (event == null) {
                                            start = FontSizeHoldGesture.start(pressed = true, distance = 0f, touchSlop = slop, timedOut = true)
                                            break
                                        }
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null) { start = FontSizeHoldGesture.Start.Cancelled; break }
                                        start = FontSizeHoldGesture.start(
                                            change.pressed, (change.position - down.position).getDistance(), slop,
                                            timedOut = android.os.SystemClock.uptimeMillis() - pressedAt >= longPressMs,
                                        )
                                        change.consume()
                                    }
                                    when (start) {
                                        FontSizeHoldGesture.Start.Tap -> { tapFontSize.value(); return@awaitEachGesture }
                                        FontSizeHoldGesture.Start.Cancelled, null -> return@awaitEachGesture
                                        FontSizeHoldGesture.Start.Glide -> Unit
                                    }
                                    // Glide: the popup is (or becomes) open and this same pointer drives it until it lifts
                                    // (Compose keeps sending it to this node outside its bounds). The highlight starts on the
                                    // applied row and moves one row per step of vertical travel from the press point
                                    // (GlideScrollPad); release applies it, see FontSizeHoldGesture.release.
                                    val sizes = TextSizeCatalog.menuSizes(currentFontSize.value.toFloat())
                                    holdOpen.value()
                                    var release = FontSizeHoldGesture.Release.Cancel
                                    var picked: FontSizePick? = null
                                    var leftButton = false
                                    val toRoot = { p: Offset -> fontSizeCoords.value?.localToRoot(p) ?: p }
                                    fontSizeHold.startGlide(toRoot(down.position).y, stepPx)
                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            val p = change.position
                                            if (p.x < 0f || p.y < 0f || p.x >= size.width || p.y >= size.height) leftButton = true
                                            val finger = toRoot(p)
                                            val overToolbar = toolbarCoords.value?.boundsInRoot()?.let { finger.y >= it.top && finger.y < it.bottom } ?: false
                                            fontSizeHold.glideTo(finger.y, suppressed = leftButton && overToolbar)
                                            if (!change.pressed) {
                                                release = FontSizeHoldGesture.release(fontSizeHold.highlighted, leftButton, overToolbar)
                                                picked = FontSizeRows.pick(fontSizeHold.highlighted, sizes)
                                                break
                                            }
                                            change.consume()
                                        }
                                    } finally {
                                        fontSizeHold.endGlide()
                                    }
                                    when (release) {
                                        FontSizeHoldGesture.Release.Apply -> picked?.let { pickFontSize.value(it) } ?: cancelFontSize.value()
                                        FontSizeHoldGesture.Release.KeepOpen -> Unit
                                        FontSizeHoldGesture.Release.Cancel -> cancelFontSize.value()
                                    }
                                }
                            }
                        }
                        .semantics {
                            role = Role.Button
                            onClick(label = fontSizeName) { onFontSize(); true }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(model.fontSizeLabel, fontSize = 17.sp, color = Color(0xFFFCFCFC).takeIf { SameSameTheme.colors.isDark } ?: c.icon)
                    Icon(AppIcons.DropdownCaret, stringResource(R.string.feature_composer_font_size), Modifier.size(19.dp).offset(x = (-1).dp), tint = c.icon)
                }
            }
            Cell(Modifier.width(TextCellWidth)) { ToolButton(AppIcons.Undo, R.string.feature_composer_undo, enabled = model.canUndo, onClick = onUndo) }
            Cell(Modifier.width(TextCellWidth)) { ToolButton(AppIcons.Redo, R.string.feature_composer_redo, enabled = model.canRedo, onClick = onRedo) }
        }
    }
}

@Composable
internal fun Cell(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) { content() }
}

/** The bar drawn under a toolbar glyph: the text colour or the highlight colour (unset highlight is an outlined black bar). */
internal sealed interface ColorBar {
    data class Text(val color: Color) : ColorBar
    data class Highlight(val color: Color) : ColorBar
}

/**
 * 32 dp tap target; [active] puts the selected pill (`#33FFFFFF`, 32 dp circle) behind the glyph. The colour [bar] is
 * 15.5 x 2.4 dp, centred 7.6 dp below the glyph centre (#26: 40 x 6 px at y 2239..2245, centre line 2222).
 */
@Composable
internal fun ToolButton(
    icon: ImageVector,
    label: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color = Color.Unspecified,
    bar: ColorBar? = null,
    onClick: () -> Unit,
) {
    val c = toolbarColors()
    val glyph = if (tint != Color.Unspecified) tint else if (enabled) c.icon else c.iconDisabled
    Box(
        modifier.size(32.dp).clip(CircleShape)
            .background(if (active) c.selected else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, stringResource(label), Modifier.size(24.dp), tint = glyph)
        when (bar) {
            is ColorBar.Text -> Box(
                Modifier.align(Alignment.Center).offset(y = 7.6.dp).size(width = 15.5.dp, height = 2.4.dp)
                    .background(if (bar.color != Color.Unspecified) bar.color else Color(0xFFDADADA).takeIf { SameSameTheme.colors.isDark } ?: c.icon),
            )
            is ColorBar.Highlight -> Box(
                Modifier.align(Alignment.Center).offset(y = 7.6.dp).size(width = 15.5.dp, height = 2.4.dp)
                    .then(
                        if (bar.color != Color.Unspecified) Modifier.background(bar.color)
                        else Modifier.background(if (SameSameTheme.colors.isDark) Color(0xFF010101) else Color.White).border(0.5.dp, Color(0xFF8F8F8F)),
                    ),
            )
            null -> Unit
        }
    }
}

@ComposerPreviews
@Composable
private fun TextToolbarPreview() = PreviewFrame {
    TextToolbar(TextToolbarModel(), {}, {}, {}, {}, {}, {}, {}, {})
}

@ComposerPreviews
@Composable
private fun TextToolbarColoursPreview() = PreviewFrame {
    TextToolbar(
        TextToolbarModel(textColor = Color(0xFFD01C1C), highlightColor = Color(0xFFFFE600), canRedo = true, openPanel = TextToolbarButton.FontColor),
        {}, {}, {}, {}, {}, {}, {}, {},
    )
}

package org.samesame.notes.feature.composer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.motion.SameSameMotion
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.fixedSp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Editor app bar (`#26`): back, the title field in the bar, then reading mode (book), attach (clip) and more (⋮).
 * Reading mode (`#95`) swaps the last three for Edit, Search and more.
 *
 * Tapping the title expands the title header ([HeaderHeight], laid over the page) with the note's info; a second tap
 * on the header's title makes it editable. Expand and collapse follow the reference's motion: one fraction over
 * [SameSameMotion.TitleHeaderMs] drives the header height, the back arrow turning into the collapse chevron, the bar's
 * actions fading out, the favourite star fading in and the page scrim; the bar title and the header title share one
 * position/scale track and cross-fade; the folder and info rows fade in once the header is open. All of it runs in
 * graphics layers and draw lambdas, so nothing recomposes per frame.
 */
@Composable
internal fun ComposerTopBar(
    title: String,
    reading: Boolean,
    onTitleChange: (String) -> Unit,
    onTitleDone: () -> Unit,
    onBack: () -> Unit,
    onReading: () -> Unit,
    onEdit: () -> Unit,
    onInsert: () -> Unit,
    onSearch: () -> Unit,
    onMore: () -> Unit,
    meta: NoteMeta = NoteMeta(),
    onToggleFavorite: () -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * With [reading]: true = the view state of an opened note (pencil Edit + Search), false = the book-icon reading
     * mode (book highlighted, no pencil; the book goes back to editing).
     */
    viewState: Boolean = true,
) {
    val colors = SameSameTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editingTitle by rememberSaveable { mutableStateOf(false) }
    // A tap on the bar's title text types into it (in the view/reading state it first switches to editing, like a body
    // tap); the rest of the bar opens the header (tap title = edit, empty space = metadata, as in the reference).
    var editingBarTitle by rememberSaveable { mutableStateOf(false) }
    var hadBarFocus by remember { mutableStateOf(false) }
    val barFocus = remember { FocusRequester() }
    val motion = rememberTitleHeaderMotion(expanded)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // `reading` clears a frame or more after the tap, so the field can only take focus once it is editable: re-run then.
    LaunchedEffect(editingBarTitle, reading) {
        if (editingBarTitle && !reading) { barFocus.requestFocus(); keyboard?.show() }
    }
    val expandHeader: () -> Unit = {
        editingBarTitle = false
        focusManager.clearFocus()
        expanded = true
    }
    val collapse: () -> Unit = {
        editingBarTitle = false
        if (editingTitle) {
            editingTitle = false
            focusManager.clearFocus()
            keyboard?.hide()
        }
        expanded = false
    }
    // The reference closes the header on back and on a tap on the page under it.
    BackHandler(enabled = expanded, onBack = collapse)
    val headerShown by remember { derivedStateOf { expanded || motion.fraction.value > 0f } }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dir = if (rtl) -1f else 1f
    val pivot = TransformOrigin(if (rtl) 1f else 0f, 0.5f)

    // Title: 21 sp bold in the bar; 30 sp regular in the header (comp_title_text_size), or 0.7 x the 51 dp field when the
    // font scale makes 30 sp taller than the field. The two share one track, scaled by the size ratio.
    val density = LocalDensity.current
    val barTitleSize = 21.sp
    val headerTitleSize: TextUnit = with(density) {
        if (30.sp.toPx() > HeaderTitleHeight.toPx()) fixedSp(HeaderTitleHeight.value * 0.7f) else 30.sp
    }
    val titleScale = with(density) { headerTitleSize.toPx() / barTitleSize.toPx() }

    val editLabel = stringResource(R.string.feature_composer_edit)
    // Title: 22.5 sp bold (#26: "Title" spans 125 px against 122 px at 22 sp); hint colour is 40% of the title colour
    // (#656565 on black); text sits ~1 dp below the bar's centre line.
    val titleStyle = TextStyle(
        fontSize = barTitleSize, fontWeight = FontWeight.Bold, color = colors.textPrimary,
        lineHeight = 24.sp, platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both),
    ) // sesl_toolbar_title_text_size 21sp
    // The bar title's tap target is exactly the text: measured so the space beside it can keep opening the header.
    val titleHint = stringResource(R.string.feature_composer_title_hint)
    val measurer = rememberTextMeasurer()
    // A long title stays on one line and ends in an ellipsis instead of running under the action icons.
    val barTitleMaxWidth = with(density) { (LocalConfiguration.current.screenWidthDp.dp - TitleStart - BarTitleEnd).toPx().toInt().coerceAtLeast(1) }
    val titleLayout = measurer.measure(
        title.ifEmpty { titleHint }, titleStyle, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = barTitleMaxWidth),
    )
    // The overlay must not grow the title box: its height feeds the header animation's scale pivot.
    val titleTapWidth = with(density) { titleLayout.size.width.toDp() }
    val titleTapHeight = with(density) { titleLayout.size.height.toDp() }
    // the reference's header (comp_title_container) is laid over the page, which stays where it is: the bar keeps its 56 dp
    // in the layout and the header hangs over what follows (the caller gives the bar a zIndex).
    Box(modifier.fillMaxWidth().height(AppBarHeight)) {
        if (headerShown) {
            // The page under the header greys out (the reference's lock view: white at 10 % on dark, black at 30 % on
            // light, scaled by the fraction). It keeps taps away from the page; a tap closes the header.
            val scrim = if (colors.isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.3f)
            Box(
                Modifier.wrapContentHeight(Alignment.Top, unbounded = true).offset(y = AppBarHeight).fillMaxWidth().height(3000.dp)
                    .graphicsLayer { alpha = motion.fraction.value }
                    .background(scrim)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { if (expanded) collapse() },
            )
            // Header background, growing from the bar's height to the header's; opaque (black in dark mode).
            val headerBg = if (colors.isDark) Color.Black else colors.background
            val onScrimTap by rememberUpdatedState { if (expanded) collapse() }
            Box(
                Modifier.wrapContentHeight(Alignment.Top, unbounded = true).fillMaxWidth().height(HeaderHeight)
                    .drawBehind { drawRect(headerBg, size = Size(size.width, headerHeightPx(motion.fraction.value))) }
                    // Taps on the header itself go nowhere; below its current (animating) edge they count as scrim taps.
                    .pointerInput(Unit) { detectTapGestures { if (it.y > headerHeightPx(motion.fraction.value)) onScrimTap() } },
            )
        }
        // Glyph centres measured from the right screen edge (#26, #95; 2.625 px/dp): more 32.8 dp, attach / search 70.7 / 68.6 dp,
        // book / edit 114.9 / 108.6 dp. Back chevron centre is 31 dp from the left, the title text starts at 56 dp.
        Row(Modifier.fillMaxWidth().height(AppBarHeight), verticalAlignment = Alignment.CenterVertically) {
            // Title-test capture (2026-10-30): the reference's title cap centre is 1.3 dp below the bar centre (its TextView keeps
            // the font padding), ours sat 1.3 dp above it with the font padding trimmed.
            Box(
                Modifier.weight(1f).padding(start = TitleStart, end = BarTitleEnd).offset(y = 1.3.dp)
                    .graphicsLayer {
                        val f = motion.fraction.value
                        translationX = TitleDx.toPx() * f * dir
                        translationY = TitleDy.toPx() * f
                        val s = 1f + (titleScale - 1f) * f
                        scaleX = s
                        scaleY = s
                        transformOrigin = pivot
                        alpha = motion.barTitleAlpha.value
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                if (title.isEmpty()) {
                    Text(titleHint, style = titleStyle.copy(color = colors.textPrimary.copy(alpha = 0.404f)), maxLines = 1)
                }
                if (editingBarTitle) {
                    BasicTextField(
                        value = title,
                        onValueChange = onTitleChange,
                        readOnly = reading,
                        singleLine = true,
                        textStyle = titleStyle,
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { onTitleDone() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(barFocus)
                            .focusProperties { canFocus = editingBarTitle && !reading }
                            .onFocusChanged {
                                if (it.isFocused) hadBarFocus = true
                                else if (hadBarFocus) { hadBarFocus = false; editingBarTitle = false }
                            },
                    )
                } else if (title.isNotEmpty()) {
                    Text(title, style = titleStyle, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                }
                if (!editingBarTitle) {
                    // Empty space beside the title opens the metadata header.
                    Box(
                        Modifier.matchParentSize().padding(start = titleTapWidth).clickable(
                            enabled = !expanded, indication = null, interactionSource = remember { MutableInteractionSource() },
                        ) { expandHeader() },
                    )
                    // The title text itself starts editing it; in the view/reading state the tap first switches the note
                    // to editing (the same intent as a body tap / the pencil button), then the title gets the caret.
                    Box(
                        Modifier.align(Alignment.CenterStart).height(titleTapHeight).width(titleTapWidth + 4.dp).clickable(
                            enabled = !expanded, indication = null, interactionSource = remember { MutableInteractionSource() },
                        ) {
                            if (reading) onEdit()
                            editingBarTitle = true
                        },
                    )
                } else if (!expanded) {
                    // While typing, the space after the text still opens the header; the text keeps caret placement.
                    Box(
                        Modifier.matchParentSize().padding(start = titleTapWidth).clickable(
                            indication = null, interactionSource = remember { MutableInteractionSource() },
                        ) { expandHeader() },
                    )
                }
            }
        }
        // Back: while the header opens it turns 90 degrees into the header's collapse chevron (the reference reuses the
        // back arrow, rotated) and moves onto the chevron's measured spot, (27.8, 31.0) dp glyph centre.
        Box(
            Modifier.align(Alignment.CenterStart).padding(start = 31.dp - SlotTouch / 2).height(AppBarHeight)
                .graphicsLayer {
                    val f = motion.fraction.value
                    translationX = CollapseDx.toPx() * f * dir
                    translationY = CollapseDy.toPx() * f
                },
            contentAlignment = Alignment.Center,
        ) {
            val label = stringResource(if (expanded) R.string.feature_composer_collapse_info else R.string.feature_composer_back)
            BarSlot(if (expanded) collapse else onBack) {
                Icon(
                    AppIcons.Back, label,
                    Modifier.size(24.dp).graphicsLayer { rotationZ = 90f * dir * motion.fraction.value },
                    tint = colors.textPrimary,
                )
            }
        }
        // The bar's actions fade out as the header opens (and can't be pressed while it is open).
        Box(Modifier.matchParentSize().graphicsLayer { alpha = 1f - motion.fraction.value }) {
            val enabled = !expanded
            if (reading && !viewState) {
                Slot(114.9.dp) {
                    BarSlot(onEdit, enabled) {
                        Box(
                            Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(AppIcons.Book, stringResource(R.string.feature_composer_reading_mode), Modifier.size(24.dp), tint = colors.accent)
                        }
                    }
                }
            } else if (reading) {
                Slot(108.6.dp) {
                    BarSlot(onEdit, enabled) { Icon(AppIcons.EditSquare, editLabel, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onBackground) }
                }
                Slot(68.6.dp) { BarIcon(AppIcons.Search, R.string.feature_composer_search, onSearch, enabled) }
            } else {
                Slot(114.9.dp) { BarIcon(AppIcons.Book, R.string.feature_composer_reading_mode, onReading, enabled) }
                Slot(70.7.dp) { BarIcon(AppIcons.Clip, R.string.feature_composer_insert, onInsert, enabled) }
            }
            Slot(32.8.dp) { BarIcon(AppIcons.More, R.string.feature_composer_more_options, onMore, enabled) }
            // Bottom divider, 0.5 dp (1 px): 0x4DDDDDDD in dark, 0x14000000 in light (#26 samples #434343).
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp)
                    .background(if (colors.isDark) Color(0x4DDDDDDD) else Color(0x14000000)),
            )
        }
        if (headerShown) {
            ExpandedHeader(
                title = title,
                reading = reading,
                meta = meta,
                titleSize = headerTitleSize,
                titleScale = titleScale,
                motion = motion,
                editing = editingTitle,
                onEditingChange = { editingTitle = it },
                // A tap on the header title edits it; from the view/reading state it first switches the note to editing.
                onTitleTap = { if (reading) onEdit(); editingTitle = true },
                onTitleChange = onTitleChange,
                onTitleDone = onTitleDone,
                onToggleFavorite = onToggleFavorite,
                modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
            )
        }
    }
}

/** The animated values of the title header; all of them are read only in layer/draw lambdas. */
@Stable
private class TitleHeaderMotion(expanded: Boolean) {
    /** 0 = bar, 1 = header open (already eased). */
    val fraction = Animatable(if (expanded) 1f else 0f)
    val barTitleAlpha = Animatable(if (expanded) 0f else 1f)
    val headerTitleAlpha = Animatable(if (expanded) 1f else 0f)
    val infoAlpha = Animatable(if (expanded) 1f else 0f)
}

/**
 * Runs the expand/collapse whenever [expanded] changes. A change mid-way restarts every value from where it is, the
 * fraction over the distance left (the reference's `(1 - f) * 333 ms`), so a tap during the animation reverses it
 * smoothly. After a configuration change the values start at the restored state, without animating.
 */
@Composable
private fun rememberTitleHeaderMotion(expanded: Boolean): TitleHeaderMotion {
    val motion = remember { TitleHeaderMotion(expanded) }
    LaunchedEffect(motion, expanded) {
        val fadeOut = tween<Float>(SameSameMotion.TitleHeaderFadeOutMs, easing = SameSameMotion.SineInOut70)
        val fadeIn = tween<Float>(SameSameMotion.TitleHeaderFadeInMs, SameSameMotion.TitleHeaderFadeInDelayMs, SameSameMotion.SineInOut70)
        val target = if (expanded) 1f else 0f
        val ms = (abs(target - motion.fraction.value) * SameSameMotion.TitleHeaderMs).roundToInt()
        coroutineScope {
            if (expanded) {
                launch { motion.barTitleAlpha.animateTo(0f, fadeOut) }
                launch { motion.headerTitleAlpha.animateTo(1f, fadeIn) }
            } else {
                launch { motion.infoAlpha.animateTo(0f, tween(SameSameMotion.TitleHeaderInfoOutMs, easing = SameSameMotion.SineInOut33)) }
                launch { motion.headerTitleAlpha.animateTo(0f, fadeOut) }
                launch { motion.barTitleAlpha.animateTo(1f, fadeIn) }
            }
            motion.fraction.animateTo(target, tween(ms, easing = SameSameMotion.SineInOut70))
            if (expanded) motion.infoAlpha.animateTo(1f, tween(SameSameMotion.TitleHeaderInfoInMs, easing = SameSameMotion.SineInOut33))
        }
    }
    return motion
}

private val AppBarHeight = 56.dp
private val TitleStart = 56.dp

/** Space kept free at the end of the bar title for the action icons (the book icon reaches ~127 dp from the edge). */
private val BarTitleEnd = 132.dp
private val SlotTouch = 40.dp

/** Bar title (start 56 dp, text centre 29.3 dp) to the header title (start 24 dp, text centre 87.5 dp). */
private val TitleDx = 24.dp - TitleStart
private val TitleDy = 58.2.dp

/**
 * Back slot centre (31, 28) dp to where the rotated arrow's glyph centre lands on the collapse chevron's (27.8, 31.0):
 * the arrow's glyph sits ~1 dp off its box centre, which after the quarter turn is 1 dp up.
 */
private val CollapseDx = (-3.2).dp
private val CollapseDy = 4.dp

private fun androidx.compose.ui.unit.Density.headerHeightPx(fraction: Float): Float =
    AppBarHeight.toPx() + (HeaderHeight - AppBarHeight).toPx() * fraction

/** Places an action so its glyph centre is [fromEnd] from the right edge of the bar. */
@Composable
private fun BoxScope.Slot(fromEnd: Dp, content: @Composable () -> Unit) {
    // The 40 dp touch target is centred in the 56 dp bar (it used to sit at the top: glyphs 7.6 dp above the title).
    Box(Modifier.align(Alignment.CenterEnd).height(AppBarHeight).padding(end = fromEnd - SlotTouch / 2), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun BarSlot(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, Modifier.size(SlotTouch), enabled = enabled) { content() }
}

@Composable
private fun BarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit, enabled: Boolean = true) {
    BarSlot(onClick, enabled) {
        Icon(icon, stringResource(label), Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onBackground)
    }
}

/** Info shown in the expanded title header. */
data class NoteMeta(
    val folderName: String? = null,
    val createdAt: Long = 0L,
    val modifiedAt: Long = 0L,
    val favorite: Boolean = false,
)

/**
 * The expanded title header's content, 212.6 dp from the top of the bar, laid out on
 * the reference's measured positions (title-compare capture, 2.625 px/dp, bar top = 0): collapse chevron glyph centre
 * (27.8, 31.0) dp (drawn by the bar: its back arrow, turned), favourite star centre 34.5 dp from the right and 29.9 dp
 * down; the title field 61 dp down, 51 dp tall, text from 24 dp (14 dp margin + 10 dp padding) to 84 dp before the end,
 * 30 sp regular (shrunk to 0.7 x 51 dp if the font scale makes it taller than the field); the folder row (24 dp icon,
 * 16 dp text) at 115.8 dp; "Last modified" and "Created" lines 15 dp each from 147.8 dp, 12 dp text (the reference's 0.8 x
 * line-height rule); grey #999999 (dark) / 30 % black (light). The opaque background is drawn by [ComposerTopBar]
 * under the bar; this content is clipped to the header's animated height.
 */
@Composable
private fun ExpandedHeader(
    title: String,
    reading: Boolean,
    meta: NoteMeta,
    titleSize: TextUnit,
    titleScale: Float,
    motion: TitleHeaderMotion,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    /** Starts editing the title (a tap on the title text); the caller leaves the view/reading state first if needed. */
    onTitleTap: () -> Unit,
    onTitleChange: (String) -> Unit,
    onTitleDone: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier,
) {
    val colors = SameSameTheme.colors
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing, reading) { if (editing && !reading) { focus.requestFocus(); keyboard?.show() } }
    // Leave editing only when the field loses the focus it had (not on the first unfocused callback after composing,
    // which would undo a restored editing state).
    var hadFocus by remember { mutableStateOf(false) }
    val infoColor = if (colors.isDark) Color(0xFF999999) else Color(0x4C000000)
    val noPadding = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
    val metaStyle = TextStyle(fontSize = fixedSp(12f), color = infoColor, platformStyle = noPadding)
    val fmt = remember { java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.getDefault()) }
    val dateOnly = remember { java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dir = if (rtl) -1f else 1f
    val pivot = TransformOrigin(if (rtl) 1f else 0f, 0.5f)
    Box(
        modifier.fillMaxWidth().height(HeaderHeight)
            .drawWithContent { clipRect(bottom = headerHeightPx(motion.fraction.value)) { this@drawWithContent.drawContent() } },
    ) {
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = -(34.5.dp - SlotTouch / 2), y = 29.9.dp - SlotTouch / 2)
                .graphicsLayer { alpha = motion.fraction.value },
        ) {
            BarSlot(onToggleFavorite) {
                Icon(if (meta.favorite) AppIcons.StarFilled else AppIcons.Star, stringResource(R.string.feature_composer_favourite), Modifier.size(24.dp), tint = colors.textPrimary)
            }
        }
        val titleStyle = TextStyle(fontSize = titleSize, fontWeight = FontWeight.Normal, color = colors.textPrimary, platformStyle = noPadding)
        Box(
            Modifier.padding(top = 61.dp, start = 24.dp, end = 84.dp).fillMaxWidth().height(HeaderTitleHeight)
                .graphicsLayer {
                    // The same track as the bar title, run backwards: starts on the bar title at its size and grows.
                    val g = 1f - motion.fraction.value
                    translationX = -TitleDx.toPx() * g * dir
                    translationY = -TitleDy.toPx() * g
                    val s = 1f + (1f / titleScale - 1f) * g
                    scaleX = s
                    scaleY = s
                    transformOrigin = pivot
                    alpha = motion.headerTitleAlpha.value
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // the reference's cap centre is 1 dp below the field centre (EditText font padding).
            Box(Modifier.fillMaxWidth().offset(y = 1.dp), contentAlignment = Alignment.CenterStart) {
                if (title.isEmpty()) Text(stringResource(R.string.feature_composer_title_hint), style = titleStyle.copy(color = colors.textPrimary.copy(alpha = 0.404f)), maxLines = 1)
                if (editing) {
                    BasicTextField(
                        value = title,
                        onValueChange = onTitleChange,
                        readOnly = reading,
                        singleLine = true,
                        textStyle = titleStyle,
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { onTitleDone() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).focusProperties { canFocus = editing && !reading }
                            .onFocusChanged {
                                if (it.isFocused) hadFocus = true
                                else if (hadFocus) { hadFocus = false; onEditingChange(false) }
                            },
                    )
                } else if (title.isNotEmpty()) {
                    Text(title, style = titleStyle, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                }
            }
            if (!editing) Box(Modifier.matchParentSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onTitleTap() })
        }
        // Folder and info rows: in once the header is open, out as soon as it starts to close.
        Box(Modifier.fillMaxWidth().height(HeaderHeight).graphicsLayer { alpha = motion.infoAlpha.value }) {
            Row(Modifier.padding(start = 24.dp, top = 115.8.dp).height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Folder, null, Modifier.size(24.dp), tint = infoColor)
                Text(
                    meta.folderName ?: stringResource(R.string.feature_composer_folders),
                    style = TextStyle(fontSize = fixedSp(16f), color = infoColor, platformStyle = noPadding),
                    modifier = Modifier.padding(start = 4.dp).offset(y = 0.4.dp), maxLines = 1,
                )
            }
            Column(Modifier.padding(start = 24.dp, top = 147.8.dp, end = 24.dp)) {
                InfoLine(stringResource(R.string.feature_composer_last_modified, fmt.format(meta.modifiedAt).replace("AM", "am").replace("PM", "pm")), metaStyle)
                InfoLine(stringResource(R.string.feature_composer_created, dateOnly.format(meta.createdAt)), metaStyle)
            }
        }
    }
}

/** A 15 dp info line; the reference's TextView draws from the top with font padding, so its cap centre is 8.95 dp down. */
@Composable
private fun InfoLine(text: String, style: TextStyle) {
    Box(Modifier.fillMaxWidth().height(15.dp)) {
        Text(text, style = style, maxLines = 1, modifier = Modifier.offset(y = 2.1.dp))
    }
}

private val HeaderHeight = 212.6.dp
private val HeaderTitleHeight = 51.dp

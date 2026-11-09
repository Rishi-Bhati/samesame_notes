package org.samesame.notes.core.designsystem.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.samesame.notes.core.designsystem.motion.SameSameMotion
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.fixed
import androidx.compose.foundation.gestures.draggable
import androidx.compose.runtime.mutableStateOf
import kotlin.math.roundToInt

/** Expanded header height of a portrait window that is tall enough (the reference measurement). */
private val ExpandedHeight = 359.25.dp

/**
 * Expanded header height in dp for a window of [windowHeightDp]. The expanded header is a share of the window height
 * chosen by size class; 0 means the toolbar only (no big title), which keeps phone landscape usable. Portrait keeps
 * the reference 359.25 dp on tall windows (0.405 of 887 dp) and scales down on shorter ones.
 */
internal fun expandedHeaderDp(windowHeightDp: Float, landscape: Boolean): Float = when {
    landscape -> when {
        windowHeightDp < 640f -> 0f
        windowHeightDp < 670f -> windowHeightDp * 0.4f
        windowHeightDp < 1080f -> windowHeightDp * 0.38f
        else -> windowHeightDp * 0.25f
    }
    windowHeightDp < 639f -> 0f
    else -> minOf(ExpandedHeight.value, windowHeightDp * 0.405f)
}
internal val CollapsedExtra = 120.dp
private val ToolbarHeight = 56.dp
/** Secondary top bar height below the status bar. */
internal val SimpleTopBarHeight = 79.6.dp
private val TitleTop = 155.75.dp

/**
 * One UI "large title" screen: an expanded header with a centred title and subtitle that collapses
 * into a toolbar (title at the start) when the content scrolls. The toolbar row (navigation icon
 * and actions) is always pinned to the bottom of the header.
 *
 * [content] should be a scrollable (e.g. lazy grid) that fills the remaining space.
 */
@Composable
fun CollapsingHeaderScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    centerCollapsedTitle: Boolean = false,
    /** When false the content does not scroll, so the title stays (or returns) expanded. */
    contentScrollable: Boolean = true,
    /** Cross-fades the title when this changes (e.g. entering selection mode); changes of [title] alone swap instantly. */
    titleKey: Any = Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.getTop(density)
    val collapsedPx = with(density) { CollapsedExtra.toPx() } + statusBar
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val expandedDp = expandedHeaderDp(
        configuration.screenHeightDp.toFloat(),
        landscape = configuration.screenWidthDp > configuration.screenHeightDp,
    )
    // Proportion 0 (or a window too short for the big title): toolbar only, the title lives in the toolbar.
    val toolbarOnly = with(density) { expandedDp.dp.toPx() } <= collapsedPx
    val expandedPx = if (toolbarOnly) collapsedPx else with(density) { expandedDp.dp.toPx() }
    val range = (expandedPx - collapsedPx).coerceAtLeast(1f)
    var offset by remember { mutableFloatStateOf(0f) }
    val scrollableNow by rememberUpdatedState(contentScrollable)

    // Removing notes, switching filter or emptying the list can leave no scrollable content behind a collapsed
    // title: it must come back down so the title is not stuck in the toolbar.
    LaunchedEffect(contentScrollable) {
        if (!contentScrollable && offset > 0f) {
            animate(offset, 0f, animationSpec = tween(SameSameMotion.ListReflowMs, easing = SameSameMotion.SineInOut90)) { value, _ ->
                offset = value
            }
        }
    }

    val connection = remember(range) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (scrollableNow && available.y < 0f && offset < range) {
                    val consumed = (-available.y).coerceAtMost(range - offset)
                    offset += consumed
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && offset > 0f) {
                    val used = available.y.coerceAtMost(offset)
                    offset -= used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }
        }
    }

    val colors = SameSameTheme.colors
    Box(modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            // The scroll offset is read in the layout/draw phases only: collapsing resizes the header (a
            // relayout) without re-composing this scaffold, its title/actions or the content below.
            Box(
                Modifier.fillMaxWidth().layout { measurable, constraints ->
                    val h = (expandedPx - offset).roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, h) { placeable.place(0, 0) }
                }.draggable(
                    // A drag on the header moves it like a drag on the list, so a tall header can never block scrolling.
                    orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                    enabled = !toolbarOnly,
                    state = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
                        if (scrollableNow) offset = (offset - delta).coerceIn(0f, range)
                    },
                ),
            ) {
                // Expanded centred title block.
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (subtitle != null) TitleTop else TitleTop + 18.3.dp)
                        .graphicsLayer {
                            if (toolbarOnly) {
                                alpha = 0f
                                return@graphicsLayer
                            }
                            // The big title travels up with the list (about 0.63 of the scroll) while it fades.
                            val fraction = (offset / range).coerceIn(0f, 1f)
                            alpha = (1f - fraction * SameSameMotion.TitleFadeK).coerceIn(0f, 1f)
                            translationY = -offset * SameSameMotion.TitleParallax
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TitleSwap(titleKey, title) { shown ->
                        Text(shown, style = SameSameText.LargeTitle.fixed(), color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    if (subtitle != null) {
                        // The subtitle goes first.
                        Text(
                            subtitle, style = SameSameText.LargeSubtitle.fixed(), color = colors.textSecondary, maxLines = 1,
                            modifier = Modifier.padding(top = 1.35.dp).graphicsLayer {
                                val fraction = (offset / range).coerceIn(0f, 1f)
                                alpha = (1f - fraction * SameSameMotion.SubtitleFadeK).coerceIn(0f, 1f)
                            },
                        )
                    }
                }
                // Toolbar row pinned to the bottom of the header.
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(ToolbarHeight)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    navigationIcon()
                    Column(
                        Modifier.weight(1f).padding(start = 8.dp).graphicsLayer {
                            val fraction = (offset / range).coerceIn(0f, 1f)
                            alpha = if (toolbarOnly) 1f else ((fraction - 0.4f) / 0.6f).coerceIn(0f, 1f)
                        },
                        verticalArrangement = Arrangement.Center,
                    ) {
                        TitleSwap(titleKey, title) { shown ->
                            Text(
                                shown, style = SameSameText.ToolbarTitle, color = colors.textPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = if (centerCollapsedTitle) TextAlign.Center else TextAlign.Start,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    actions()
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(connection)) { content() }
            bottomBar()
        }
        Box(Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))) {
            floatingActionButton()
        }
    }
}

/**
 * Cross-fades between titles when [key] changes; while the key stays the same the text updates in place, and the
 * outgoing title keeps the text it had.
 */
@Composable
internal fun TitleSwap(key: Any, title: String, content: @Composable (String) -> Unit) {
    val texts = remember { HashMap<Any, String>() }
    texts[key] = title
    Crossfade(targetState = key, animationSpec = tween(SameSameMotion.TitleSwapMs, easing = LinearEasing), label = "title") { k ->
        content(texts[k] ?: title)
    }
}

/** A plain title-at-start top bar (One UI style) for secondary screens: back arrow + bold title. */
@Composable
fun SimpleTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            // Ref #13/#20/#24: toolbar row centre 89.5dp from the screen top (48dp under the status bar), content from 121dp.
            .height(SimpleTopBarHeight)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 3.6.dp).height(ToolbarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigationIcon()
            Box(Modifier.weight(1f).padding(start = 4.4.dp)) { title() }
            actions()
        }
    }
}

package org.samesame.notes.feature.composer.panels

import androidx.compose.foundation.selection.toggleable
import org.samesame.notes.core.designsystem.icon.AppIcons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.fixedSp
import org.samesame.notes.feature.composer.R
import kotlin.math.roundToInt

/** Panel width on the 411 dp reference phone (`#28`: 671 px card, 56 dp side margins). */
internal val PanelWidth = 300.6.dp
private val PanelShape = RoundedCornerShape(26.dp) // #28: corner radius ~68 px

/**
 * Floating card used by every panel (`#28`, `#33`, `#47`, `#49`, `#52`): [PanelWidth] wide,
 * 24 dp inner padding, bold 20 sp title at the top-left, optional trailing header actions.
 */
@Composable
internal fun PanelCard(
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = PanelWidth,
    /** Pen / highlighter panels have a 1 dp #454545 stroke (#33); the text and colour panels have none (#28, #29). */
    stroked: Boolean = false,
    /** While a popup is open over the panel (#110) the content and stroke drop to 30% on an unchanged #252525 fill. */
    dimmed: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.widthIn(max = width).fillMaxWidth(),
        shape = PanelShape,
        color = SameSameTheme.colors.dialog,
        // #28: no stroke in dark (#252525 on the page); light keeps the #C7C7C7 stroke of the style popup; #33 strokes #454545.
        border = if (stroked) BorderStroke(1.dp, if (SameSameTheme.colors.isDark) (if (dimmed) Color(0xFF2F2F2F) else Color(0xFF454545)) else Color(0xFFC7C7C7))
        else if (SameSameTheme.colors.isDark) null else BorderStroke(0.5.dp, Color(0xFFC7C7C7)),
        shadowElevation = 2.dp,
    ) {
        // Scrolls when the window is shorter than the panel (landscape phones): the card shrinks to the window and the foot stays reachable.
        androidx.compose.foundation.layout.Box(Modifier.alpha(if (dimmed) 0.3f else 1f).verticalScroll(rememberScrollState())) { content() }
    }
}

@Composable
internal fun PanelHeader(
    title: String?,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    endPadding: androidx.compose.ui.unit.Dp = 16.dp,
    titleSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
) {
    Row(
        modifier.fillMaxWidth().height(56.dp).padding(start = 24.dp, end = endPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = if (titleSize == androidx.compose.ui.unit.TextUnit.Unspecified) fixedSp(19.8f) else titleSize, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
        } else {
            Box(Modifier.weight(1f))
        }
        actions()
        if (onClose != null) HeaderIcon(AppIcons.Close, stringResource(R.string.feature_composer_close), onClose)
    }
}

@Composable
internal fun HeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick, role = Role.Button), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** Dotted horizontal rule (`#43`, `#47`). */
@Composable
internal fun DottedDivider(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawLine(color, Offset(0f, 1f), Offset(size.width, 1f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 3.dp.toPx()), 0f))
    }
}

/** "Cancel | Done" bold text actions at the foot of dialogs (`#43`, `#52`). */
@Composable
internal fun DialogActions(onCancel: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionText(stringResource(R.string.feature_composer_cancel), onCancel, Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.onSurface)) // #43: 3 x 42 px (1 x 16 dp)
        ActionText(stringResource(R.string.feature_composer_done), onDone, Modifier.weight(1f))
    }
}

@Composable
private fun ActionText(text: String, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick, role = Role.Button), contentAlignment = Alignment.Center) {
        Text(text, fontSize = fixedSp(19.8f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Size row (`#33`): value label (14 sp) above the thumb, "-" and "+" glyphs centred 36.4 dp from the panel edges, a 164 dp
 * 1..[max] track between them (19.8 dp gaps; 2 dp thick, 17 dp thumb, both tinted [tint]; inactive #E3E0E0). The track centre is
 * the vertical centre of the 64 dp row; the label's baseline is 13.7 dp above it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SizeSliderRow(
    value: Int,
    onValue: (Int) -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
    max: Int = 100,
    min: Int = 1,
    suffix: String = "",
    description: String = "size",
    rowHeight: androidx.compose.ui.unit.Dp = 64.dp,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val inactive = if (SameSameTheme.colors.isDark) Color(0xFFE3E0E0) else onSurface.copy(alpha = 0.6f)
    Row(modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 24.4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape).clickable { onValue((value - 1).coerceAtLeast(min)) },
            contentAlignment = Alignment.Center) {
            Icon(AppIcons.Remove, stringResource(R.string.feature_composer_size_decrease), Modifier.size(26.dp), tint = onSurface)
        }
        Spacer(Modifier.width(19.8.dp))
        BoxWithConstraints(Modifier.weight(1f).height(rowHeight)) {
            val thumb = 17.dp
            val frac = (value - min.toFloat()) / (max - min).toFloat().coerceAtLeast(1f)
            val travel = maxWidth - thumb
            Text(
                value.toString() + suffix, fontSize = fixedSp(13.9f), color = onSurface,
                modifier = Modifier.align(Alignment.TopStart)
                    .offset(x = travel * frac + thumb / 2 - 20.dp, y = rowHeight / 2 - 26.7.dp).width(40.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Slider(
                value = value.toFloat(), onValueChange = { onValue(it.roundToInt()) }, valueRange = min.toFloat()..max.toFloat(),
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(40.dp)
                    .semantics { contentDescription = description },
                colors = SliderDefaults.colors(
                    thumbColor = tint, activeTrackColor = tint,
                    inactiveTrackColor = inactive,
                ),
                thumb = { Box(Modifier.size(thumb).clip(CircleShape).background(tint)) },
                track = { st ->
                    SliderDefaults.Track(st, Modifier.height(2.dp), thumbTrackGapSize = 0.dp, trackInsideCornerSize = 0.dp,
                        drawStopIndicator = null,
                        colors = SliderDefaults.colors(activeTrackColor = tint, inactiveTrackColor = inactive))
                },
            )
        }
        Spacer(Modifier.width(19.8.dp))
        Box(Modifier.size(24.dp).clip(CircleShape).clickable { onValue((value + 1).coerceAtMost(max)) },
            contentAlignment = Alignment.Center) {
            Icon(AppIcons.Add, stringResource(R.string.feature_composer_size_increase), Modifier.size(26.dp), tint = onSurface)
        }
    }
}

/**
 * Opacity row (#81): like [SizeSliderRow] but the track is a 164 x 20.2 dp pill over a checkerboard fading from transparent to
 * [tint], with a white 17 dp thumb and the "45%" label above it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun OpacityRow(
    value: Int,
    onValue: (Int) -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
    rowHeight: androidx.compose.ui.unit.Dp = 64.dp,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dark = SameSameTheme.colors.isDark
    Row(modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 24.4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape).clickable { onValue((value - 1).coerceAtLeast(1)) }, contentAlignment = Alignment.Center) {
            Icon(AppIcons.Remove, stringResource(R.string.feature_composer_size_decrease), Modifier.size(26.dp), tint = onSurface)
        }
        Spacer(Modifier.width(19.8.dp))
        BoxWithConstraints(Modifier.weight(1f).height(rowHeight)) {
            val thumb = 17.dp
            val frac = (value - 1f) / 99f
            val travel = maxWidth - thumb
            Text(
                "$value%", fontSize = fixedSp(13.9f), color = onSurface,
                modifier = Modifier.align(Alignment.TopStart)
                    .offset(x = travel * frac + thumb / 2 - 20.dp, y = rowHeight / 2 - 26.7.dp).width(40.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Canvas(Modifier.align(Alignment.Center).fillMaxWidth().height(20.2.dp).clip(RoundedCornerShape(50))) {
                val cell = 5.8.dp.toPx()
                val a = if (dark) Color(0xFF3A3A3A) else Color(0xFFE0E0E0)
                val b = if (dark) Color(0xFF2B2B2B) else Color(0xFFC8C8C8)
                var y = 0f; var row = 0
                while (y < size.height) {
                    var x = 0f; var col = 0
                    while (x < size.width) {
                        drawRect(if ((row + col) % 2 == 0) a else b, Offset(x, y), androidx.compose.ui.geometry.Size(cell, cell))
                        x += cell; col++
                    }
                    y += cell; row++
                }
                drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(tint.copy(alpha = 0f), tint)))
            }
            Slider(
                value = value.toFloat(), onValueChange = { onValue(it.roundToInt()) }, valueRange = 1f..100f,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(40.dp).semantics { contentDescription = "opacity" },
                thumb = { Box(Modifier.size(thumb).clip(CircleShape).background(Color(0xFFFCFCFC))) },
                track = { },
            )
        }
        Spacer(Modifier.width(19.8.dp))
        Box(Modifier.size(24.dp).clip(CircleShape).clickable { onValue((value + 1).coerceAtMost(100)) }, contentAlignment = Alignment.Center) {
            Icon(AppIcons.Add, stringResource(R.string.feature_composer_size_increase), Modifier.size(26.dp), tint = onSurface)
        }
    }
}

/**
 * Swatch strip pager (`#29`, `#33`, `#39`-`#42`): rounded strip of 9 cells, selected cell raised with a check, and page dots
 * below. Pages: optional Recent page (7 recent colours, then an eyedropper cell in the 8th slot and the "colour sets to show"
 * button as the 9th), then one page per chosen palette set (8 colours and the custom-colour tile as the 9th cell).
 */
@Composable
internal fun SwatchPager(
    selected: Color?,
    recent: List<Color>,
    showRecentPage: Boolean,
    onColor: (Color) -> Unit,
    onCustom: () -> Unit,
    modifier: Modifier = Modifier,
    /** Explicit pages (text colour panels); when null the pages are [paletteSets] in the current theme's row. */
    palette: List<List<Color>>? = null,
    paletteSets: List<Int> = ColorPalettes.DEFAULT_SELECTED,
    /** Recent page: eyedropper cell (null hides it, leaving 8 recent slots). */
    onEyedropper: (() -> Unit)? = null,
    /** Recent page: "Select colour sets to show" button. */
    onPaletteSettings: (() -> Unit)? = null,
    /** Space between the pager and the page dots: 22.5 dp in #29, 21 dp in #33. */
    dotsTopPadding: androidx.compose.ui.unit.Dp = 22.5.dp,
) {
    val dark = SameSameTheme.colors.isDark
    val sets = palette ?: ColorPalettes.normalize(paletteSets).map { ColorPalettes.colors(it, dark) }
    val recentSlots = if (onEyedropper != null) ColorPalettes.RECENT_VISIBLE_WITH_EYEDROPPER else ColorPalettes.RECENT_CAPACITY
    val pages: List<List<Color>> = (if (showRecentPage) listOf(recent.take(recentSlots)) else emptyList()) + sets
    val firstPalette = if (showRecentPage) 1 else 0
    // #33: a colour that is not on any page (the default blue, a picked colour) checks the custom-colour tile.
    val customSelected = ColorPalettes.isCustomSelection(pages, selected)
    val pager = rememberPagerState(initialPage = firstPalette) { pages.size }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(pager, Modifier.fillMaxWidth().height(StripHeight + 4.dp), verticalAlignment = Alignment.CenterVertically) { page ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (showRecentPage && page == 0) SwatchStrip(pages[0], selected, onColor, onCustom, recentPage = true, onEyedropper = onEyedropper, onPaletteSettings = onPaletteSettings)
                else SwatchStrip(pages[page], selected, onColor, onCustom, customSelected = customSelected)
            }
        }
        // #29: dots are 5 dp (13 px) on a 10 dp pitch, top 22.5 dp below the pager.
        if (pages.size > 1) Row(Modifier.padding(top = dotsTopPadding).height(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            pages.indices.forEach { i ->
                val active = pager.currentPage == i
                val dotModifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    scope.launch { pager.animateScrollToPage(i) }
                }.semantics { contentDescription = "Page ${i + 1} of ${pages.size}" }
                if (showRecentPage && i == 0) {
                    Icon(AppIcons.Schedule, stringResource(R.string.feature_composer_recent_colours),
                        dotModifier.size(10.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 1f else 0.2f))
                } else {
                    Box(dotModifier.size(5.dp).clip(CircleShape).background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 1f else 0.2f))) // #29: #FCFCFC active, #505050 inactive
                }
            }
        }
    }
}

/** #29: cells are 73 px squares (27.8 dp); 9 cells span x 211..868 px. */
private val CellWidth = 27.8.dp
private val StripHeight = 27.8.dp

@Composable
private fun SwatchStrip(
    colors: List<Color>,
    selected: Color?,
    onColor: (Color) -> Unit,
    onCustom: () -> Unit,
    recentPage: Boolean = false,
    onEyedropper: (() -> Unit)? = null,
    onPaletteSettings: (() -> Unit)? = null,
    /** The selection is a custom colour: the last tile is raised and checked. */
    customSelected: Boolean = false,
) {
    val shape = RoundedCornerShape(7.dp)
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(Modifier.height(StripHeight + 4.dp)) {
        Row(Modifier.align(Alignment.Center).clip(shape)) {
            repeat(8) { i ->
                val c = colors.getOrNull(i)
                if (recentPage && i == 7 && onEyedropper != null) {
                    val desc = stringResource(R.string.feature_composer_eyedropper)
                    Box(Modifier.size(CellWidth, StripHeight).background(onSurface.copy(alpha = 0.08f)).clickable(onClick = onEyedropper)
                        .semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
                        Icon(AppIcons.Eyedropper, null, Modifier.size(20.dp), tint = onSurface)
                    }
                } else Box(
                    Modifier.size(CellWidth, StripHeight)
                        .background(c ?: onSurface.copy(alpha = 0.08f))
                        .then(if (c != null) Modifier.clickable { onColor(c) }
                            .semantics { contentDescription = "#%06X".format(c.toArgbInt() and 0xFFFFFF) } else Modifier),
                )
            }
            if (recentPage) {
                val desc = stringResource(R.string.feature_composer_colour_sets_to_show)
                Box(Modifier.size(CellWidth, StripHeight).background(onSurface.copy(alpha = 0.08f))
                    .then(if (onPaletteSettings != null) Modifier.clickable(onClick = onPaletteSettings) else Modifier)
                    .semantics { contentDescription = desc }, contentAlignment = Alignment.Center) { DotsGlyph(Modifier.size(20.dp)) }
            } else {
                val customDesc = stringResource(R.string.feature_composer_colour_settings)
                CustomTile(Modifier.size(CellWidth, StripHeight).clickable(onClick = onCustom)
                    .semantics { contentDescription = customDesc })
            }
        }
        // Raised selected cell (#29: 83 x 77 px, #DADADA, soft shadow, white check).
        val idx = ColorPalettes.indexOf(colors, selected)
        if (customSelected && !recentPage) {
            Box(
                Modifier.offset(x = CellWidth * 8 - 1.9.dp).align(Alignment.CenterStart)
                    .size(CellWidth + 3.8.dp, StripHeight + 1.5.dp)
                    .shadow(2.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                CustomTile(Modifier.fillMaxSize())
                Icon(AppIcons.Check, null, Modifier.size(22.dp), tint = Color.White)
            }
        }
        if (idx >= 0 && !(recentPage && onEyedropper != null && idx >= 7)) {
            Box(
                Modifier.offset(x = CellWidth * idx - 1.9.dp).align(Alignment.CenterStart)
                    .size(CellWidth + 3.8.dp, StripHeight + 1.5.dp)
                    .shadow(2.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)).background(colors[idx]),
                contentAlignment = Alignment.Center,
            ) {
                val dark = colors[idx].luminance() < 0.6f
                Icon(AppIcons.Check, null, Modifier.size(22.dp), tint = if (dark) Color.White else Color(0xFF333333))
            }
        }
    }
}

@Composable
internal fun CustomTile(modifier: Modifier = Modifier) {
    val t = ComposerColors.customTiles
    Column(modifier) {
        Row(Modifier.weight(1f)) {
            Box(Modifier.weight(1f).fillMaxWidthAndHeight().background(t[0])); Box(Modifier.weight(1f).fillMaxWidthAndHeight().background(t[1]))
        }
        Row(Modifier.weight(1f)) {
            Box(Modifier.weight(1f).fillMaxWidthAndHeight().background(t[2])); Box(Modifier.weight(1f).fillMaxWidthAndHeight().background(t[3]))
        }
    }
}

private fun Modifier.fillMaxWidthAndHeight(): Modifier = this.fillMaxSize()

internal fun Color.toArgbInt(): Int = this.toArgb()
internal fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Switch of `#47`/`#49`: track 37.3 x 22.1 dp (accent on, #666660 off), 19.4 dp #FCFCFC thumb inset 1.4 dp. */
@Composable
internal fun PanelSwitch(checked: Boolean, onChecked: (Boolean) -> Unit) {
    val track = if (checked) SameSameTheme.colors.accent else Color(0xFF666660)
    Box(
        Modifier.size(37.3.dp, 22.1.dp).clip(CircleShape).background(track)
            .toggleable(checked, role = Role.Switch, onValueChange = onChecked).padding(1.4.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(19.4.dp).clip(CircleShape).background(Color(0xFFFCFCFC))) }
}


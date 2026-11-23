package org.samesame.notes.feature.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import dev.oneuiproject.oneui.R as Oui

/**
 * The look part of every widget's settings: a stage with the live preview (on a wallpaper-like backdrop, with a Day/Night
 * switch so Match dark mode and opacity can be judged), the widget-specific [content] card, the background swatches and
 * the stepped opacity slider.
 */
@Composable
internal fun WidgetSettingsBody(
    style: WidgetStyle,
    onStyle: (WidgetStyle) -> Unit,
    widgetSize: DpSize,
    /** The heading of the [content] card; null = "Shows". */
    contentHeading: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    preview: @Composable (roles: WidgetColorRoles, night: Boolean, size: DpSize) -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    var night by rememberSaveable { mutableStateOf(systemDark) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PreviewStage(night, widgetSize) { size ->
            // The stage stands in for the wallpaper: light by day, dark by night.
            preview(WidgetTheme.roles(style, wallpaperDarkText = !night), night, size)
        }
        DayNightSwitch(night) { night = it }
        if (content != null) SectionCard(contentHeading ?: stringResource(R.string.widget_section_content), content = content)
        SectionCard(stringResource(R.string.widget_section_background)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Swatch(WidgetBackground.White, R.string.widget_style_white, style.background) { onStyle(style.copy(background = it)) }
                Swatch(WidgetBackground.Black, R.string.widget_style_black, style.background) { onStyle(style.copy(background = it)) }
                Swatch(WidgetBackground.MatchDark, R.string.widget_style_match_short, style.background) { onStyle(style.copy(background = it)) }
            }
            if (style.background == WidgetBackground.MatchDark) {
                Text(
                    stringResource(R.string.widget_style_match), color = SameSameTheme.colors.textSecondary, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        SectionCard(stringResource(R.string.widget_section_opacity)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val c = SameSameTheme.colors
                Slider(
                    value = style.opacity.toFloat(),
                    onValueChange = { onStyle(style.copy(opacity = WidgetStyle.stepOpacity(it))) },
                    valueRange = WidgetStyle.MIN_OPACITY.toFloat()..WidgetStyle.MAX_OPACITY.toFloat(),
                    steps = (WidgetStyle.MAX_OPACITY - WidgetStyle.MIN_OPACITY) / WidgetStyle.OPACITY_STEP - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.pill,
                        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text("${style.opacity}%", color = c.textPrimary, fontSize = 15.sp, modifier = Modifier.width(52.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            if (style.opacity < WidgetTheme.SEE_THROUGH_BELOW) {
                Text(
                    stringResource(R.string.widget_opacity_hint), color = SameSameTheme.colors.textSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Backdrop + the preview drawn at the widget's real size, scaled down if the screen is narrower. */
@Composable
private fun PreviewStage(night: Boolean, widgetSize: DpSize, preview: @Composable (DpSize) -> Unit) {
    val backdrop = if (night) {
        Brush.linearGradient(listOf(Color(0xFF1B2140), Color(0xFF3B2241), Color(0xFF14262E)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFFFD8C4), Color(0xFFE9D9F7), Color(0xFFC8DCF5)))
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(28.dp)).background(backdrop)) {
        val size = DpSize(widgetSize.width, min(widgetSize.height, MAX_PREVIEW_HEIGHT))
        val room = maxWidth - 32.dp
        val scale = if (size.width > room) room / size.width else 1f
        Box(
            Modifier.fillMaxWidth().height(size.height * scale + 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.requiredSize(size).graphicsLayer(scaleX = scale, scaleY = scale)) { preview(size) }
        }
    }
}

private val MAX_PREVIEW_HEIGHT = 320.dp

@Composable
private fun DayNightSwitch(night: Boolean, onChange: (Boolean) -> Unit) {
    val c = SameSameTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.Center) {
        Row(Modifier.clip(RoundedCornerShape(20.dp)).background(c.pill).padding(3.dp)) {
            listOf(false to R.string.widget_preview_day, true to R.string.widget_preview_night).forEach { (value, label) ->
                val selected = value == night
                Row(
                    Modifier.clip(RoundedCornerShape(17.dp)).background(if (selected) c.card else Color.Transparent)
                        .clickable { onChange(value) }.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(if (value) Oui.drawable.ic_oui_weather_night else Oui.drawable.ic_oui_weather_sunny),
                        contentDescription = null, tint = if (selected) c.accent else c.iconMuted, modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(label), color = if (selected) c.textPrimary else c.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Swatch(value: WidgetBackground, label: Int, current: WidgetBackground, onPick: (WidgetBackground) -> Unit) {
    val c = SameSameTheme.colors
    val selected = value == current
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable { onPick(value) }.padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).border(2.dp, if (selected) c.accent else Color.Transparent, CircleShape).padding(5.dp)
                .clip(CircleShape).border(1.dp, c.divider, CircleShape),
        ) {
            when (value) {
                WidgetBackground.White -> Box(Modifier.fillMaxSize().background(WidgetTokens.surfaceWhite))
                WidgetBackground.Black -> Box(Modifier.fillMaxSize().background(WidgetTokens.surfaceBlack))
                WidgetBackground.MatchDark -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(WidgetTokens.surfaceWhite))
                    Box(Modifier.weight(1f).fillMaxHeight().background(WidgetTokens.surfaceNight))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(label), color = if (selected) c.textPrimary else c.textSecondary, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

// ---- Compose replicas of the widgets for the settings preview (same tokens and geometry as the Glance layouts) ----

private val OuterShape = RoundedCornerShape(24.dp)
private val InnerShape = RoundedCornerShape(14.dp)

@Composable
private fun ReplicaFrame(roles: WidgetColorRoles, night: Boolean, padding: Dp = WidgetDims.Inset, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().clip(OuterShape).background(roles.surface.pick(night)).padding(padding)) { content() }
}

@Composable
private fun ReplicaHeader(title: String, roles: WidgetColorRoles, night: Boolean, icon: Int? = null, plus: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(WidgetDims.HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(4.dp))
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = roles.accent.pick(night), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(title, color = roles.text.pick(night), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (plus) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(roles.accent.pick(night)), contentAlignment = Alignment.Center) {
                Icon(painterResource(Oui.drawable.ic_oui_add), contentDescription = null, tint = roles.onAccent.pick(night), modifier = Modifier.size(16.dp))
            }
        }
    }
}

private val replicaCreatorIcons = listOf(
    Oui.drawable.ic_oui_keyboard, Oui.drawable.ic_oui_pen_fountain, Oui.drawable.ic_oui_brush_outline,
    Oui.drawable.ic_oui_image_outline, Oui.drawable.ic_oui_voice,
)
private val replicaCreatorLabels = listOf(
    R.string.widget_creator_label_pen, R.string.widget_creator_label_brush, R.string.widget_creator_label_image, R.string.widget_creator_label_voice,
)

@Composable
internal fun CreatorReplica(roles: WidgetColorRoles, night: Boolean, size: DpSize, appName: String, appIcon: ImageBitmap?) {
    val layout = CreatorLayout.forSize(size)
    @Composable
    fun buttons(icons: List<Int>, modifier: Modifier) {
        val button = (size.height - 12.dp).coerceIn(32.dp, 44.dp)
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            icons.forEachIndexed { i, icon ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(button).clip(CircleShape).background(if (i == 0) roles.accent.pick(night) else roles.tile.pick(night)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(icon), contentDescription = null, tint = if (i == 0) roles.onAccent.pick(night) else roles.icon.pick(night), modifier = Modifier.size(button / 2))
                    }
                }
            }
        }
    }
    @Composable
    fun appHeader() {
        Row(Modifier.fillMaxWidth().height(WidgetDims.HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(4.dp))
            appIcon?.let { Image(it, contentDescription = null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(appName, color = roles.text.pick(night), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
    when (layout) {
        CreatorLayout.Small -> ReplicaFrame(roles, night, 6.dp) { buttons(replicaCreatorIcons.take(2), Modifier.fillMaxSize()) }
        CreatorLayout.Strip -> ReplicaFrame(roles, night, 6.dp) { buttons(replicaCreatorIcons, Modifier.fillMaxSize()) }
        CreatorLayout.Tall -> ReplicaFrame(roles, night) {
            Column(Modifier.fillMaxSize()) {
                appHeader()
                Spacer(Modifier.height(4.dp))
                buttons(replicaCreatorIcons, Modifier.fillMaxWidth().weight(1f))
            }
        }
        CreatorLayout.Full -> ReplicaFrame(roles, night) {
            Column(Modifier.fillMaxSize()) {
                appHeader()
                Spacer(Modifier.height(WidgetDims.Gap))
                Row(
                    Modifier.fillMaxWidth().weight(1f).clip(InnerShape).background(roles.accentTile.pick(night)).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(Oui.drawable.ic_oui_keyboard), contentDescription = null, tint = roles.accent.pick(night), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.widget_creator_write), color = roles.accent.pick(night), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(WidgetDims.Gap))
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(WidgetDims.Gap)) {
                    replicaCreatorIcons.drop(1).forEachIndexed { i, icon ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(InnerShape).background(roles.tile.pick(night)),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(painterResource(icon), contentDescription = null, tint = roles.icon.pick(night), modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(replicaCreatorLabels[i]), color = roles.textSecondary.pick(night), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SingleNoteReplica(roles: WidgetColorRoles, night: Boolean, size: DpSize, title: String, date: String, snippet: String, page: ImageBitmap?) {
    ReplicaFrame(roles, night) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(WidgetDims.HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.ifBlank { stringResource(R.string.widget_untitled) }, color = roles.text.pick(night), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(date, color = roles.textSecondary.pick(night), fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(end = 4.dp))
            }
            if (SingleNoteLayout.pageArea(size) == null) {
                if (snippet.isNotEmpty()) Text(snippet, color = roles.textSecondary.pick(night), fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
                return@Column
            }
            Spacer(Modifier.height(WidgetDims.Gap / 2))
            if (page != null) {
                Image(page, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize().clip(InnerShape))
            } else {
                Box(Modifier.fillMaxSize().clip(InnerShape).background(roles.tile.pick(night)).padding(12.dp)) {
                    Text(snippet.ifEmpty { title }, color = roles.text.pick(night), fontSize = 13.sp, maxLines = 8)
                }
            }
        }
    }
}

/** One preview row of the list / pages replicas. */
internal class ReplicaRow(val title: String, val snippet: String, val date: String, val thumb: ImageBitmap?, val locked: Boolean, val favorite: Boolean)

@Composable
internal fun NoteListReplica(roles: WidgetColorRoles, night: Boolean, size: DpSize, heading: String, icon: Int, rows: List<ReplicaRow>) {
    val thumbs = size.width >= NoteListLayout.THUMBS_FROM_WIDTH
    ReplicaFrame(roles, night) {
        Column(Modifier.fillMaxSize()) {
            ReplicaHeader(heading, roles, night, icon, plus = true)
            Spacer(Modifier.height(WidgetDims.Gap / 2))
            if (rows.isEmpty()) ReplicaEmpty(roles, night)
            rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(InnerShape).background(roles.tile.pick(night)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (thumbs) {
                        ReplicaThumb(row, roles, night, Modifier.size(NoteListLayout.THUMB), RoundedCornerShape(WidgetDims.ThumbRadius))
                        Spacer(Modifier.width(10.dp))
                    } else {
                        Spacer(Modifier.width(4.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                row.title.ifBlank { stringResource(R.string.widget_untitled) }, color = roles.text.pick(night), fontSize = 14.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            if (row.favorite) {
                                Spacer(Modifier.width(4.dp))
                                Icon(painterResource(Oui.drawable.ic_oui_favorite_on), contentDescription = null, tint = roles.accent.pick(night), modifier = Modifier.size(12.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(row.date, color = roles.textSecondary.pick(night), fontSize = 11.sp, maxLines = 1)
                        }
                        val second = if (row.locked) stringResource(R.string.widget_locked_note) else row.snippet
                        if (second.isNotEmpty() || thumbs) Text(second, color = roles.textSecondary.pick(night), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun NoteGridReplica(roles: WidgetColorRoles, night: Boolean, size: DpSize, heading: String, icon: Int, rows: List<ReplicaRow>) {
    val cols = NoteGridLayout.columns(size.width)
    val thumbH = NoteGridLayout.cellWidth(size.width) * NoteGridLayout.ASPECT
    ReplicaFrame(roles, night) {
        Column(Modifier.fillMaxSize()) {
            ReplicaHeader(heading, roles, night, icon, plus = true)
            Spacer(Modifier.height(WidgetDims.Gap / 2))
            if (rows.isEmpty()) ReplicaEmpty(roles, night)
            rows.chunked(cols).forEach { line ->
                Row(Modifier.fillMaxWidth()) {
                    for (i in 0 until cols) {
                        val row = line.getOrNull(i)
                        Column(Modifier.weight(1f).padding(horizontal = NoteGridLayout.CELL_GAP / 2).padding(bottom = 8.dp)) {
                            if (row != null) {
                                ReplicaThumb(row, roles, night, Modifier.fillMaxWidth().height(thumbH), InnerShape)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    row.title.ifBlank { stringResource(R.string.widget_untitled) }, color = roles.text.pick(night), fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplicaThumb(row: ReplicaRow, roles: WidgetColorRoles, night: Boolean, modifier: Modifier, shape: RoundedCornerShape) {
    if (row.thumb != null && !row.locked) {
        Image(row.thumb, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = modifier.clip(shape))
    } else {
        Box(modifier.clip(shape).background(roles.tile.pick(night)), contentAlignment = Alignment.Center) {
            Icon(
                painterResource(if (row.locked) Oui.drawable.ic_oui_lock else Oui.drawable.ic_oui_notes), contentDescription = null,
                tint = roles.textSecondary.pick(night), modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ColumnScope.ReplicaEmpty(roles: WidgetColorRoles, night: Boolean) {
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.widget_list_empty), color = roles.textSecondary.pick(night), fontSize = 13.sp)
    }
}

@Composable
internal fun QuickCaptureReplica(roles: WidgetColorRoles, night: Boolean, size: DpSize, buttons: CaptureButtons) {
    @Composable
    fun round(b: CaptureButton, side: Dp, primary: Boolean = false) {
        if (b == CaptureButton.None) {
            Spacer(Modifier.size(side))
            return
        }
        val icon = if (primary && b == CaptureButton.NewNote) Oui.drawable.ic_oui_add else b.icon
        Box(
            Modifier.size(side).clip(CircleShape).background(if (primary) roles.accent.pick(night) else roles.tile.pick(night)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon), contentDescription = null,
                tint = if (primary) roles.onAccent.pick(night) else roles.icon.pick(night),
                modifier = Modifier.size(side * if (primary) 0.42f else 0.5f),
            )
        }
    }

    @Composable
    fun bar(modifier: Modifier) {
        val main = buttons.centre
        Row(modifier.clip(RoundedCornerShape(28.dp)).background(roles.tile.pick(night)).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(roles.accent.pick(night)), contentAlignment = Alignment.Center) {
                Icon(painterResource(main.icon), contentDescription = null, tint = roles.onAccent.pick(night), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(if (main == CaptureButton.NewNote) R.string.widget_capture_bar else main.title),
                color = roles.textSecondary.pick(night), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
        }
    }
    val shown = buttons.shown
    when (QuickCaptureLayout.forSize(size)) {
        CaptureShape.Square -> ReplicaFrame(roles, night, QuickCaptureLayout.SQUARE_PADDING) {
            val corner = QuickCaptureLayout.corner(size)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth()) {
                        round(buttons.corners[CaptureButtons.TOP_LEFT], corner)
                        Spacer(Modifier.weight(1f))
                        round(buttons.corners[CaptureButtons.TOP_RIGHT], corner)
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth()) {
                        round(buttons.corners[CaptureButtons.BOTTOM_LEFT], corner)
                        Spacer(Modifier.weight(1f))
                        round(buttons.corners[CaptureButtons.BOTTOM_RIGHT], corner)
                    }
                }
                round(buttons.centre, QuickCaptureLayout.centre(size), primary = true)
            }
        }
        CaptureShape.Tiles -> ReplicaFrame(roles, night) {
            Column(Modifier.fillMaxSize()) {
                if (shown.isEmpty()) {
                    bar(Modifier.fillMaxSize())
                    return@Column
                }
                bar(Modifier.fillMaxWidth().height(56.dp))
                Spacer(Modifier.height(WidgetDims.Gap))
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(WidgetDims.Gap)) {
                    shown.forEach { b ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(InnerShape).background(roles.tile.pick(night)),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(painterResource(b.icon), contentDescription = null, tint = roles.icon.pick(night), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(b.label), color = roles.textSecondary.pick(night), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
            }
        }
        CaptureShape.Bar -> ReplicaFrame(roles, night, 8.dp) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                bar(Modifier.weight(1f).fillMaxHeight())
                shown.take(QuickCaptureLayout.sideButtons(size.width)).forEach { b ->
                    Spacer(Modifier.width(QuickCaptureLayout.BUTTON_GAP))
                    Box(Modifier.size(QuickCaptureLayout.BUTTON).clip(CircleShape).background(roles.tile.pick(night)), contentAlignment = Alignment.Center) {
                        Icon(painterResource(b.icon), contentDescription = null, tint = roles.icon.pick(night), modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

/**
 * The quick-capture "Buttons" card: one row per slot (main button, then the corners in reading order) showing its action;
 * tapping a row opens the list of actions under it. Corners can be left empty.
 */
@Composable
internal fun ColumnScope.CaptureButtonsEditor(buttons: CaptureButtons, onChange: (CaptureButtons) -> Unit) {
    // -1 = the main button, 0..3 = the corners; null = all closed.
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    val slots = listOf(
        -1 to R.string.widget_slot_centre,
        CaptureButtons.TOP_LEFT to R.string.widget_slot_top_left,
        CaptureButtons.TOP_RIGHT to R.string.widget_slot_top_right,
        CaptureButtons.BOTTOM_LEFT to R.string.widget_slot_bottom_left,
        CaptureButtons.BOTTOM_RIGHT to R.string.widget_slot_bottom_right,
    )
    slots.forEachIndexed { i, (slot, name) ->
        if (i > 0) CardDivider()
        val current = if (slot < 0) buttons.centre else buttons.corners[slot]
        ChoiceRow(
            title = stringResource(name),
            subtitle = stringResource(current.title),
            icon = current.icon.takeIf { it != 0 } ?: Oui.drawable.ic_oui_close,
            iconTint = if (slot < 0) SameSameTheme.colors.accent else null,
            onClick = { open = if (open == slot) null else slot },
        )
        if (open == slot) {
            val choices = if (slot < 0) CaptureButton.centreChoices else CaptureButton.cornerChoices
            choices.forEach { b ->
                ChoiceRow(
                    title = stringResource(b.title),
                    icon = b.icon.takeIf { it != 0 } ?: Oui.drawable.ic_oui_close,
                    selected = b == current,
                    indent = 24.dp,
                    onClick = {
                        onChange(if (slot < 0) buttons.withCentre(b) else buttons.withCorner(slot, b))
                        open = null
                    },
                )
            }
        }
    }
    Text(
        stringResource(R.string.widget_buttons_hint), color = SameSameTheme.colors.textSecondary, fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
    )
}

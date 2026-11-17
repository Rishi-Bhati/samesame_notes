package org.samesame.notes.feature.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.model.NoteId
import dev.oneuiproject.oneui.R as Oui

/**
 * The settings screen shared by the three widgets: a live [preview] of the widget on top, then the background choice
 * (white, black, match dark mode) and the opacity slider. [extra] adds a widget-specific action (change note/folder).
 */
@Composable
internal fun WidgetSettingsScreen(
    title: String,
    style: WidgetStyle,
    onStyle: (WidgetStyle) -> Unit,
    onDone: () -> Unit,
    extra: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    preview: @Composable (WidgetStyle, Boolean) -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConfigTitle(title)
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(150.dp)
                .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(110.dp)) { preview(style, style.isDark(systemDark)) }
        }
        Text(
            stringResource(R.string.widget_style_background), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 4.dp),
        )
        BackgroundOption(R.string.widget_style_white, style.background == WidgetBackground.White) { onStyle(style.copy(background = WidgetBackground.White)) }
        BackgroundOption(R.string.widget_style_black, style.background == WidgetBackground.Black) { onStyle(style.copy(background = WidgetBackground.Black)) }
        BackgroundOption(R.string.widget_style_match, style.background == WidgetBackground.MatchDark) { onStyle(style.copy(background = WidgetBackground.MatchDark)) }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.widget_style_opacity), color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            Text("${style.opacity}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = style.opacity.toFloat(), onValueChange = { onStyle(style.copy(opacity = WidgetStyle.clampOpacity(it.toInt()))) },
            valueRange = WidgetStyle.MIN_OPACITY.toFloat()..WidgetStyle.MAX_OPACITY.toFloat(),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (extra != null) extra()
            Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.widget_done)) }
        }
    }
}

@Composable
internal fun ChangeButton(textRes: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier) { Text(stringResource(textRes), maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun BackgroundOption(label: Int, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(stringResource(label), color = MaterialTheme.colorScheme.onBackground)
    }
}

/** The widget surface of [style] for the previews: its background colour, opacity and rounded corners. */
@Composable
private fun PreviewSurface(style: WidgetStyle, dark: Boolean, content: @Composable (WidgetColorRoles) -> Unit) {
    val roles = WidgetColorRules.roles(style)
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(roles.surface.pick(dark))) { content(roles) }
}

/** Single note: the note's page (rendered light or dark to match the background) inside the surface. */
@Composable
internal fun SingleNotePreview(noteId: NoteId?, title: String, style: WidgetStyle, dark: Boolean) {
    val notes = WidgetEntryPoint.get(LocalContext.current).notes()
    val page by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, noteId, dark) {
        value = noteId?.let { WidgetBitmaps.page(notes, it, dark)?.asImageBitmap() }
    }
    PreviewSurface(style, dark) { roles ->
        val bitmap = page
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap, contentDescription = title, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(title.ifBlank { stringResource(R.string.widget_choose_note) }, color = roles.textSecondary.pick(dark), fontSize = 13.sp, maxLines = 2)
            }
        }
    }
}

/** Note list: the header and up to three sample rows. */
@Composable
internal fun NoteListPreview(heading: String, rows: List<String>, style: WidgetStyle, dark: Boolean) {
    PreviewSurface(style, dark) { roles ->
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(heading, color = roles.text.pick(dark), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            rows.take(3).forEach {
                Text(it.ifBlank { stringResource(R.string.widget_untitled) }, color = roles.text.pick(dark), fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Creator: the row of mode buttons. */
@Composable
internal fun CreatorPreview(style: WidgetStyle, dark: Boolean) {
    PreviewSurface(style, dark) { roles ->
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(Oui.drawable.ic_oui_keyboard, Oui.drawable.ic_oui_pen_fountain, Oui.drawable.ic_oui_brush_outline, Oui.drawable.ic_oui_image_outline, R.drawable.ic_widget_mic).forEach {
                Icon(painterResource(it), contentDescription = null, tint = roles.icon.pick(dark), modifier = Modifier.size(24.dp))
            }
        }
    }
}

package org.samesame.notes.feature.widgets

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import kotlinx.coroutines.flow.first
import dev.oneuiproject.oneui.R as Oui

/** One note's first page on the home screen. Tapping it opens the note. */
class SingleNoteWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = WidgetEntryPoint.get(context)
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val noteId = ep.pins().noteOf(widgetId)
        val note = noteId?.let { runCatching { ep.notes().observeNote(it).first() }.getOrNull() }
        val state = WidgetContent.single(pinned = noteId != null, note = note)
        // Nothing but a ready note reaches the renderer: a locked or missing note never produces a bitmap.
        // The page is drawn dark when the widget's background is dark (black, or "match" in night mode).
        val style = WidgetStyleStore(context).get(widgetId)
        val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val page = (state as? SingleNoteState.Ready)?.let { WidgetBitmaps.page(ep.notes(), it.noteId, dark = style.isDark(night)) }
        provideContent { SingleNoteContent(widgetId, state, page, widgetPalette(style)) }
    }
}

@Composable
private fun SingleNoteContent(widgetId: Int, state: SingleNoteState, page: Bitmap?, p: WidgetPalette) {
    val context = LocalContext.current
    val tap = when (state) {
        is SingleNoteState.Ready -> actionStartActivity(WidgetIntents.openNote(context, state.noteId.value))
        SingleNoteState.NotChosen -> actionStartActivity(NotePickerActivity.intent(context, widgetId))
        SingleNoteState.Locked, SingleNoteState.Gone -> actionStartActivity(WidgetIntents.openApp(context))
    }
    Box(
        modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp).background(p.surface).clickable(tap),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // The page sits inside the (possibly translucent) background, which shows around it.
            state is SingleNoteState.Ready && page != null ->
                Image(
                    ImageProvider(page), contentDescription = state.title,
                    modifier = GlanceModifier.fillMaxSize().padding(6.dp).cornerRadius(10.dp), contentScale = ContentScale.Crop,
                )
            state is SingleNoteState.Ready -> Message(state.title.ifBlank { context.getString(R.string.widget_untitled) }, null, p)
            state == SingleNoteState.Locked -> Message(context.getString(R.string.widget_locked_note), Oui.drawable.ic_oui_lock, p)
            state == SingleNoteState.Gone -> Message(context.getString(R.string.widget_note_gone), null, p)
            else -> Message(context.getString(R.string.widget_choose_note), Oui.drawable.ic_oui_notes, p)
        }
    }
}

@Composable
private fun Message(text: String, icon: Int?, p: WidgetPalette) {
    Column(modifier = GlanceModifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Image(ImageProvider(icon), contentDescription = null, modifier = GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(p.icon))
            Spacer(GlanceModifier.height(6.dp))
        }
        Text(text, style = TextStyle(color = p.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center), maxLines = 3)
    }
}

class SingleNoteWidgetReceiver : WidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = SingleNoteWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val pins = WidgetEntryPoint.get(context).pins()
        appWidgetIds.forEach(pins::unpin)
        val styles = WidgetStyleStore(context)
        appWidgetIds.forEach(styles::clear)
        super.onDeleted(context, appWidgetIds)
    }
}

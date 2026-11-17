package org.samesame.notes.feature.widgets

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import kotlinx.coroutines.flow.first
import org.samesame.notes.core.model.NoteFilter
import org.samesame.notes.core.model.SortOrder
import dev.oneuiproject.oneui.R as Oui

/** Recent notes (or one folder's notes) as a scrollable list. Tapping a row opens the note. */
class NoteListWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = WidgetEntryPoint.get(context)
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val folderId = WidgetListConfig(context).folderOf(widgetId)
        val folder = folderId?.let { fid -> runCatching { ep.folders().observeFolders().first() }.getOrNull()?.firstOrNull { it.id == fid } }
        val folderGone = folderId != null && folder == null
        val filter = if (folderId != null) NoteFilter.InFolder(folderId) else NoteFilter.All
        val notes = if (folderGone) emptyList() else runCatching { ep.notes().observeNotes(filter, SortOrder()).first() }.getOrDefault(emptyList())
        val rows = WidgetContent.rows(notes)
        val style = WidgetStyleStore(context).get(widgetId)
        val title = when {
            folderGone -> context.getString(R.string.widget_list_title)
            folder != null -> folder.name
            else -> context.getString(R.string.widget_list_title)
        }
        val times = rows.associate { it.noteId.value to DateUtils.formatDateTime(context, it.modifiedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL) }
        provideContent { NoteListContent(widgetId, title, rows, times, folderGone, widgetPalette(style)) }
    }
}

@Composable
private fun NoteListContent(widgetId: Int, title: String, rows: List<ListRow>, times: Map<String, String>, folderGone: Boolean, p: WidgetPalette) {
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp).background(p.surface)) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(NoteListConfigureActivity.intent(context, widgetId))),
                style = TextStyle(color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Box(
                modifier = GlanceModifier.size(40.dp).clickable(actionStartActivity(WidgetIntents.createNote(context, StartAction.Keypad))),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    ImageProvider(Oui.drawable.ic_oui_add), contentDescription = context.getString(R.string.widget_new_note),
                    modifier = GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(p.accent),
                )
            }
        }
        if (rows.isEmpty()) {
            Box(modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(WidgetIntents.openApp(context))), contentAlignment = Alignment.Center) {
                Text(
                    context.getString(if (folderGone) R.string.widget_folder_gone else R.string.widget_list_empty),
                    style = TextStyle(color = p.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center),
                    modifier = GlanceModifier.padding(16.dp),
                )
            }
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(rows, itemId = { it.noteId.value.hashCode().toLong() }) { row ->
                    NoteRow(row, times[row.noteId.value].orEmpty(), p)
                }
            }
        }
    }
}

@Composable
private fun NoteRow(row: ListRow, time: String, p: WidgetPalette) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(actionStartActivity(WidgetIntents.openNote(context, row.noteId.value))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                row.title.ifBlank { context.getString(R.string.widget_untitled) },
                style = TextStyle(color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            if (time.isNotEmpty()) Text(time, style = TextStyle(color = p.textSecondary, fontSize = 12.sp), maxLines = 1)
        }
        if (row.locked) {
            Spacer(GlanceModifier.width(8.dp))
            Image(ImageProvider(Oui.drawable.ic_oui_lock), contentDescription = context.getString(R.string.widget_locked_note), modifier = GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(p.icon))
        } else if (row.favorite) {
            Spacer(GlanceModifier.width(8.dp))
            Image(ImageProvider(Oui.drawable.ic_oui_favorite_on), contentDescription = null, modifier = GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(p.accent))
        }
    }
}

class NoteListWidgetReceiver : WidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = NoteListWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val config = WidgetListConfig(context)
        appWidgetIds.forEach(config::clear)
        val styles = WidgetStyleStore(context)
        appWidgetIds.forEach(styles::clear)
        super.onDeleted(context, appWidgetIds)
    }
}

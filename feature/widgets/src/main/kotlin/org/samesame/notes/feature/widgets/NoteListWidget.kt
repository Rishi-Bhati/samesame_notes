package org.samesame.notes.feature.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
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
import androidx.glance.text.TextStyle
import kotlinx.coroutines.flow.first
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.SortOrder
import dev.oneuiproject.oneui.R as Oui

/** Recent notes, favourites or one folder as a scrollable list of rows (page thumbnail, title, text line, date). */
class NoteListWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val style = WidgetStyleStore(context).get(widgetId)
        val wallpaper = wallpaperPrefersDarkText(context)
        val dark = style.isDark(isNight(context), wallpaper)
        val thumbPx = (NoteListLayout.THUMB.value * context.resources.displayMetrics.density).toInt()
        val data = ListWidgetData.load(context, widgetId, dark, maxRows = org.samesame.notes.core.model.Limits.WIDGET_LIST_MAX_ROWS, thumbW = thumbPx, aspect = 1f)
        provideContent { NoteListContent(widgetId, data, widgetPalette(style, wallpaper)) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val data = ListWidgetData.sample(context)
        provideContent { NoteListContent(0, data, widgetPalette(WidgetStyle())) }
    }
}

object NoteListLayout {
    val THUMB = 40.dp
    /** Narrower than this, rows drop the thumbnail. */
    val THUMBS_FROM_WIDTH = 200.dp
}

/** What the list and pages widgets draw: the source's name, its rows and their thumbnails (never for a locked note). */
internal class ListWidgetData(
    val source: ListSource,
    val title: String,
    val rows: List<ListRow>,
    val dates: Map<NoteId, String>,
    val thumbs: Map<NoteId, ImageProvider>,
    val sourceGone: Boolean,
) {
    val folderId: FolderId? get() = (source as? ListSource.Folder)?.id

    companion object {
        suspend fun load(context: Context, widgetId: Int, dark: Boolean, maxRows: Int, thumbW: Int, aspect: Float, thumbRows: Int = maxRows): ListWidgetData {
            val ep = WidgetEntryPoint.get(context)
            val source = WidgetListConfig(context).sourceOf(widgetId)
            val folder = (source as? ListSource.Folder)?.let { s ->
                runCatching { ep.folders().observeFolders().first() }.getOrNull()?.firstOrNull { it.id == s.id && !it.isInTrash }
            }
            val gone = source is ListSource.Folder && folder == null
            val notes = if (gone) emptyList() else runCatching { ep.notes().observeNotes(source.filter(), SortOrder()).first() }.getOrDefault(emptyList())
            val rows = WidgetContent.rows(notes, maxRows)
            val title = when (source) {
                ListSource.Recent -> context.getString(R.string.widget_source_recent)
                ListSource.Favorites -> context.getString(R.string.widget_source_favorites)
                is ListSource.Folder -> folder?.name ?: context.getString(R.string.widget_source_recent)
            }
            val withThumbs = rows.filter { !it.locked }.take(thumbRows)
            val (w, h) = WidgetBitmaps.thumbSize(withThumbs.size, thumbW, aspect)
            val thumbs = buildMap {
                for (row in withThumbs) WidgetBitmaps.thumbnail(ep.notes(), row.noteId, dark, w, h)?.let { put(row.noteId, ImageProvider(it)) }
            }
            return ListWidgetData(source, title, rows, rows.associate { it.noteId to widgetDate(context, it.modifiedAt) }, thumbs, gone)
        }

        /** Sample content for the widget picker's generated preview (no real notes). */
        fun sample(context: Context): ListWidgetData {
            val now = System.currentTimeMillis()
            val rows = listOf(
                ListRow(NoteId("p1"), context.getString(R.string.widget_sample_title), false, true, now, context.getString(R.string.widget_sample_body)),
                ListRow(NoteId("p2"), context.getString(R.string.widget_sample_title_2), false, false, now - 86_400_000L, context.getString(R.string.widget_sample_body_2)),
                ListRow(NoteId("p3"), context.getString(R.string.widget_sample_title_3), false, false, now - 3 * 86_400_000L, context.getString(R.string.widget_sample_body_3)),
            )
            val page = ImageProvider(R.drawable.widget_pv_page)
            return ListWidgetData(
                ListSource.Recent, context.getString(R.string.widget_source_recent), rows,
                rows.associate { it.noteId to widgetDate(context, it.modifiedAt) }, rows.associate { it.noteId to page }, false,
            )
        }
    }
}

/** The header shared by the list and pages widgets: source icon + name (tap = settings) and an accent "+" (new note there). */
@Composable
internal fun SourceHeader(widgetId: Int, data: ListWidgetData, p: WidgetPalette, configure: Intent) {
    val context = LocalContext.current
    val icon = when (data.source) {
        ListSource.Recent -> Oui.drawable.ic_oui_recent_outline
        ListSource.Favorites -> Oui.drawable.ic_oui_favorite_on
        is ListSource.Folder -> Oui.drawable.ic_oui_folder_outline
    }
    WidgetHeader(
        title = data.title, p = p, leadingIcon = icon,
        onTitle = if (widgetId != 0) actionStartActivity(configure) else null,
        trailing = {
            RoundButton(
                Oui.drawable.ic_oui_add, context.getString(R.string.widget_new_note),
                actionStartActivity(WidgetIntents.createNote(context, StartAction.Keypad, data.folderId, favorite = data.source == ListSource.Favorites)),
                p, primary = true, size = 28.dp, iconSize = 16.dp,
            )
        },
    )
}

@Composable
internal fun NoteListContent(widgetId: Int, data: ListWidgetData, p: WidgetPalette) {
    val context = LocalContext.current
    val configure = NoteListConfigureActivity.intent(context, widgetId, ListKind.List)
    WidgetFrame(p) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            SourceHeader(widgetId, data, p, configure)
            Spacer(GlanceModifier.height(WidgetDims.Gap / 2))
            ListBody(data, p, configure) { rows ->
                val thumbs = LocalSize.current.width >= NoteListLayout.THUMBS_FROM_WIDTH
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(rows, itemId = { it.noteId.value.hashCode().toLong() }) { row ->
                        Box(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            NoteRow(row, data.dates[row.noteId].orEmpty(), if (thumbs) data.thumbs[row.noteId] else null, thumbs, p)
                        }
                    }
                }
            }
        }
    }
}

/** Empty and gone states of the list and pages widgets, or [content] with the rows. */
@Composable
internal fun ListBody(data: ListWidgetData, p: WidgetPalette, configure: Intent, content: @Composable (List<ListRow>) -> Unit) {
    val context = LocalContext.current
    when {
        data.sourceGone -> Box(GlanceModifier.fillMaxSize().clickable(actionStartActivity(configure))) {
            StateMessage(Oui.drawable.ic_oui_folder_outline, context.getString(R.string.widget_folder_gone), context.getString(R.string.widget_folder_gone_hint), p)
        }
        data.rows.isEmpty() -> Box(
            GlanceModifier.fillMaxSize().clickable(
                actionStartActivity(WidgetIntents.createNote(context, StartAction.Keypad, data.folderId, favorite = data.source == ListSource.Favorites)),
            ),
        ) {
            val (title, hint) = if (data.source == ListSource.Favorites) R.string.widget_favorites_empty to R.string.widget_favorites_empty_hint
            else R.string.widget_list_empty to R.string.widget_list_empty_hint
            StateMessage(Oui.drawable.ic_oui_notes, context.getString(title), context.getString(hint), p)
        }
        else -> content(data.rows)
    }
}

@Composable
private fun NoteRow(row: ListRow, date: String, thumb: ImageProvider?, showThumb: Boolean, p: WidgetPalette) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth().rounded(p.roles.tile).padding(8.dp)
            .clickable(actionStartActivity(WidgetIntents.openNote(context, row.noteId.value))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showThumb) {
            Thumb(thumb, row.locked, p, GlanceModifier.size(NoteListLayout.THUMB))
            Spacer(GlanceModifier.width(10.dp))
        } else {
            Spacer(GlanceModifier.width(4.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title.ifBlank { context.getString(R.string.widget_untitled) }, maxLines = 1, modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
                if (row.favorite) {
                    Spacer(GlanceModifier.width(4.dp))
                    Image(ImageProvider(Oui.drawable.ic_oui_favorite_on), contentDescription = null, modifier = GlanceModifier.size(12.dp), colorFilter = ColorFilter.tint(p.accent))
                }
                Spacer(GlanceModifier.width(6.dp))
                Text(date, maxLines = 1, style = TextStyle(color = p.textSecondary, fontSize = 11.sp))
            }
            val second = when {
                row.locked -> context.getString(R.string.widget_locked_note)
                else -> row.snippet
            }
            if (second.isNotEmpty() || showThumb) {
                Text(second, maxLines = 1, style = TextStyle(color = p.textSecondary, fontSize = 12.sp))
            }
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

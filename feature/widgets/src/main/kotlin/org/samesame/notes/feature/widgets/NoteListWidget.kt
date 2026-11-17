package org.samesame.notes.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
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
import androidx.glance.appwidget.lazy.GridCells
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.LazyVerticalGrid
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
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.SortOrder
import dev.oneuiproject.oneui.R as Oui

/**
 * Notes: recent notes, favourites or one folder, shown as a list of rows (page thumbnail, title, text line, date) or as
 * pages (a grid of first pages). [NotesDisplay.Auto] picks by size. One widget for both, so the picker shows one notes
 * widget; widgets placed from the former Pages provider ([NoteGridWidgetReceiver], now hidden from the picker) keep
 * working and default to pages.
 */
class NoteListWidget : LoggingGlanceWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        WidgetLog.i("notes #$widgetId provideGlance")
        val fallbackDisplay = defaultDisplayOf(context, widgetId)
        provideContent {
            val version by WidgetSignals.version.collectAsState()
            val style = remember(version) { WidgetStyleStore(context).get(widgetId) }
            val wallpaper = remember(version) { wallpaperPrefersDarkText(context) }
            val mode = remember(version) { WidgetListConfig(context).displayOf(widgetId, fallbackDisplay) }
            val dark = remember(version, style, wallpaper) { style.isDark(isNight(context), wallpaper) }
            val p = widgetPalette(style, wallpaper)
            val data by produceState<Loaded<ListWidgetData>>(Loaded.Loading, version) {
                value = loadWidgetData("notes #$widgetId rows") { ListWidgetData.load(context, widgetId) }
            }
            when (val d = data) {
                Loaded.Loading -> LoadingFrame(p)
                Loaded.Failed -> FailedFrame(p)
                is Loaded.Ready -> {
                    val size = LocalSize.current
                    val display = NotesDisplay.resolve(mode, size)
                    val thumbs by produceState<Map<NoteId, ImageProvider>>(emptyMap(), version, display, size, dark) {
                        value = (loadWidgetData("notes #$widgetId thumbs $display") {
                            loadThumbs(context, d.value.rows, display, size, dark)
                        } as? Loaded.Ready)?.value.orEmpty()
                    }
                    NotesContent(widgetId, d.value, thumbs, display, p)
                }
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val data = ListWidgetData.sample(context)
        val page = ImageProvider(R.drawable.widget_pv_page)
        provideContent {
            val display = NotesDisplay.resolve(NotesDisplay.Auto, LocalSize.current)
            NotesContent(0, data, data.rows.associate { it.noteId to page }, display, widgetPalette(WidgetStyle()))
        }
    }

    private suspend fun loadThumbs(context: Context, rows: List<ListRow>, display: NotesDisplay, size: DpSize, dark: Boolean): Map<NoteId, ImageProvider> {
        val density = context.resources.displayMetrics.density
        val pages = display == NotesDisplay.Pages
        // A narrow list draws no thumbnails at all.
        if (!pages && size.width < NoteListLayout.THUMBS_FROM_WIDTH) return emptyMap()
        val widthDp = if (pages) NoteGridLayout.cellWidth(size.width) else NoteListLayout.THUMB
        val aspect = if (pages) NoteGridLayout.ASPECT else 1f
        val wanted = rows.filter { !it.locked }.take(if (pages) NoteGridLayout.MAX_ITEMS else rows.size)
        val (w, h) = WidgetBitmaps.thumbSize(wanted.size, (widthDp.value * density).toInt(), aspect)
        val notes = WidgetEntryPoint.get(context).notes()
        return buildMap {
            for (row in wanted) WidgetBitmaps.thumbnail(notes, row.noteId, dark, w, h)?.let { put(row.noteId, ImageProvider(it)) }
        }
    }

    internal companion object {
        /** Widgets placed from the former Pages provider show pages unless their settings say otherwise. */
        fun defaultDisplayOf(context: Context, widgetId: Int): NotesDisplay {
            val provider = runCatching { AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)?.provider?.className }.getOrNull()
            return if (provider == NoteGridWidgetReceiver::class.java.name) NotesDisplay.Pages else NotesDisplay.Auto
        }
    }
}

/** How the notes widget lays its notes out. */
enum class NotesDisplay(val key: String) {
    /** Pages when the widget is big enough for a row of readable pages, a list otherwise. */
    Auto("auto"),
    List("list"),
    Pages("pages"),
    ;

    companion object {
        /** From this size up, [Auto] shows pages (about 4x3 cells on a phone: a row of pages with their titles fits). */
        val PAGES_FROM = DpSize(250.dp, 230.dp)

        fun fromKey(key: String?): NotesDisplay? = entries.firstOrNull { it.key == key }

        /** The concrete layout ([List] or [Pages]) for [mode] at [size]. */
        fun resolve(mode: NotesDisplay, size: DpSize): NotesDisplay = when (mode) {
            List, Pages -> mode
            Auto -> if (size.width >= PAGES_FROM.width && size.height >= PAGES_FROM.height) Pages else List
        }
    }
}

object NoteListLayout {
    val THUMB = 40.dp
    /** Narrower than this, rows drop the thumbnail. */
    val THUMBS_FROM_WIDTH = 200.dp
}

/** Geometry of the pages layout (pure). */
object NoteGridLayout {
    /** At most this many pages get a thumbnail (they share one memory budget, see [WidgetBitmaps.THUMB_BUDGET_BYTES]). */
    const val MAX_ITEMS = 12

    /** Thumbnail height / width: a little taller than wide, like the top of a page. */
    const val ASPECT = 1.2f
    val DEFAULT_WIDTH = 300.dp
    private val MIN_CELL = 92.dp
    val CELL_GAP = 6.dp

    /** 2 to 4 columns, as many as fit at [MIN_CELL] each. */
    fun columns(widgetWidth: Dp): Int {
        val inner = widgetWidth - WidgetDims.Inset * 2
        return (inner.value / MIN_CELL.value).toInt().coerceIn(2, 4)
    }

    fun cellWidth(widgetWidth: Dp): Dp {
        val inner = widgetWidth - WidgetDims.Inset * 2
        val cols = columns(widgetWidth)
        return (inner - CELL_GAP * (cols - 1)) / cols
    }
}

/** What the notes widget draws: the source's name and its rows. Thumbnails load separately (never for a locked note). */
internal class ListWidgetData(
    val source: ListSource,
    val title: String,
    val rows: List<ListRow>,
    val dates: Map<NoteId, String>,
    val sourceGone: Boolean,
) {
    val folderId: FolderId? get() = (source as? ListSource.Folder)?.id

    companion object {
        suspend fun load(context: Context, widgetId: Int): ListWidgetData {
            val ep = WidgetEntryPoint.get(context)
            val source = WidgetListConfig(context).sourceOf(widgetId)
            val folder = (source as? ListSource.Folder)?.let { s ->
                ep.folders().observeFolders().first().firstOrNull { it.id == s.id && !it.isInTrash }
            }
            val gone = source is ListSource.Folder && folder == null
            val notes = if (gone) emptyList() else ep.notes().observeNotes(source.filter(), SortOrder()).first()
            val rows = WidgetContent.rows(notes, Limits.WIDGET_LIST_MAX_ROWS)
            val title = when (source) {
                ListSource.Recent -> context.getString(R.string.widget_source_recent)
                ListSource.Favorites -> context.getString(R.string.widget_source_favorites)
                is ListSource.Folder -> folder?.name ?: context.getString(R.string.widget_source_recent)
            }
            return ListWidgetData(source, title, rows, rows.associate { it.noteId to widgetDate(context, it.modifiedAt) }, gone)
        }

        /** Sample content for the widget picker's generated preview (no real notes). */
        fun sample(context: Context): ListWidgetData {
            val now = System.currentTimeMillis()
            val rows = listOf(
                ListRow(NoteId("p1"), context.getString(R.string.widget_sample_title), false, true, now, context.getString(R.string.widget_sample_body)),
                ListRow(NoteId("p2"), context.getString(R.string.widget_sample_title_2), false, false, now - 86_400_000L, context.getString(R.string.widget_sample_body_2)),
                ListRow(NoteId("p3"), context.getString(R.string.widget_sample_title_3), false, false, now - 3 * 86_400_000L, context.getString(R.string.widget_sample_body_3)),
            )
            return ListWidgetData(ListSource.Recent, context.getString(R.string.widget_source_recent), rows, rows.associate { it.noteId to widgetDate(context, it.modifiedAt) }, false)
        }
    }
}

@Composable
internal fun NotesContent(widgetId: Int, data: ListWidgetData, thumbs: Map<NoteId, ImageProvider>, display: NotesDisplay, p: WidgetPalette) {
    val context = LocalContext.current
    val configure = NoteListConfigureActivity.intent(context, widgetId)
    val width = LocalSize.current.width
    WidgetFrame(p) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            SourceHeader(widgetId, data, p, configure)
            Spacer(GlanceModifier.height(WidgetDims.Gap / 2))
            ListBody(data, p, configure) { rows ->
                if (display == NotesDisplay.Pages) {
                    val thumbH = NoteGridLayout.cellWidth(width) * NoteGridLayout.ASPECT
                    LazyVerticalGrid(gridCells = GridCells.Fixed(NoteGridLayout.columns(width)), modifier = GlanceModifier.fillMaxSize()) {
                        items(rows, itemId = { it.noteId.value.hashCode().toLong() }) { row -> PageCell(row, thumbs[row.noteId], thumbH, p) }
                    }
                } else {
                    val showThumbs = width >= NoteListLayout.THUMBS_FROM_WIDTH
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(rows, itemId = { it.noteId.value.hashCode().toLong() }) { row ->
                            Box(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                NoteRow(row, data.dates[row.noteId].orEmpty(), thumbs[row.noteId], showThumbs, p)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The notes widget's header: source icon + name (tap = settings) and an accent "+" (new note there). */
@Composable
private fun SourceHeader(widgetId: Int, data: ListWidgetData, p: WidgetPalette, configure: Intent) {
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

/** Empty and gone states, or [content] with the rows. */
@Composable
private fun ListBody(data: ListWidgetData, p: WidgetPalette, configure: Intent, content: @Composable (List<ListRow>) -> Unit) {
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
            val second = if (row.locked) context.getString(R.string.widget_locked_note) else row.snippet
            if (second.isNotEmpty() || showThumb) {
                Text(second, maxLines = 1, style = TextStyle(color = p.textSecondary, fontSize = 12.sp))
            }
        }
    }
}

@Composable
private fun PageCell(row: ListRow, thumb: ImageProvider?, thumbH: Dp, p: WidgetPalette) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxWidth().padding(start = NoteGridLayout.CELL_GAP / 2, end = NoteGridLayout.CELL_GAP / 2, bottom = 8.dp)
            .clickable(actionStartActivity(WidgetIntents.openNote(context, row.noteId.value))),
    ) {
        Thumb(thumb, row.locked, p, GlanceModifier.fillMaxWidth().height(thumbH), radius = null)
        Spacer(GlanceModifier.height(4.dp))
        Row(modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (row.favorite) {
                Image(ImageProvider(Oui.drawable.ic_oui_favorite_on), contentDescription = null, modifier = GlanceModifier.size(11.dp), colorFilter = ColorFilter.tint(p.accent))
                Spacer(GlanceModifier.width(3.dp))
            }
            Text(
                row.title.ifBlank { context.getString(R.string.widget_untitled) }, maxLines = 1,
                style = TextStyle(color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            )
        }
    }
}

/** Cleans up a removed notes widget's settings (shared by both receivers). */
private fun clearNotesWidgets(context: Context, appWidgetIds: IntArray) {
    val config = WidgetListConfig(context)
    appWidgetIds.forEach(config::clear)
    val styles = WidgetStyleStore(context)
    appWidgetIds.forEach(styles::clear)
}

class NoteListWidgetReceiver : WidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = NoteListWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        clearNotesWidgets(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

/**
 * The former Pages provider: hidden from the widget picker (`hide_from_picker`) but still registered, so a Pages widget
 * already on a home screen keeps working; it is the same notes widget, defaulting to pages.
 */
class NoteGridWidgetReceiver : WidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = NoteListWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        clearNotesWidgets(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

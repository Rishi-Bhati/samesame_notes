package org.samesame.notes.feature.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
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
import androidx.glance.appwidget.lazy.LazyVerticalGrid
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
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
import dev.oneuiproject.oneui.R as Oui

/**
 * Pages: the first pages of recent notes, favourites or one folder as a grid of thumbnails with their titles. A handwriting
 * app's notes are recognised by how they look, not by their titles; this shelf shows them at a glance. Tap opens the note.
 */
class NoteGridWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val manager = GlanceAppWidgetManager(context)
        val widgetId = manager.getAppWidgetId(id)
        val style = WidgetStyleStore(context).get(widgetId)
        val wallpaper = wallpaperPrefersDarkText(context)
        val dark = style.isDark(isNight(context), wallpaper)
        // Thumbnails are cut once, for the widest size the launcher reports (the narrower one scales them down).
        val widest = runCatching { manager.getAppWidgetSizes(id) }.getOrDefault(emptyList()).maxByOrNull { it.width.value }
        val cellW = NoteGridLayout.cellWidth(widest?.width ?: NoteGridLayout.DEFAULT_WIDTH)
        val thumbPx = (cellW.value * context.resources.displayMetrics.density).toInt()
        val data = ListWidgetData.load(context, widgetId, dark, maxRows = NoteGridLayout.MAX_ITEMS, thumbW = thumbPx, aspect = NoteGridLayout.ASPECT)
        provideContent { NoteGridContent(widgetId, data, widgetPalette(style, wallpaper)) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val data = ListWidgetData.sample(context)
        provideContent { NoteGridContent(0, data, widgetPalette(WidgetStyle())) }
    }
}

/** Geometry of the pages grid (pure). */
object NoteGridLayout {
    /** At most this many pages (thumbnails share one memory budget, see [WidgetBitmaps.THUMB_BUDGET_BYTES]). */
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

@Composable
internal fun NoteGridContent(widgetId: Int, data: ListWidgetData, p: WidgetPalette) {
    val context = LocalContext.current
    val configure = NoteListConfigureActivity.intent(context, widgetId, ListKind.Pages)
    val width = LocalSize.current.width
    val cols = NoteGridLayout.columns(width)
    val thumbH = NoteGridLayout.cellWidth(width) * NoteGridLayout.ASPECT
    WidgetFrame(p) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            SourceHeader(widgetId, data, p, configure)
            Spacer(GlanceModifier.height(WidgetDims.Gap / 2))
            ListBody(data, p, configure) { rows ->
                LazyVerticalGrid(gridCells = GridCells.Fixed(cols), modifier = GlanceModifier.fillMaxSize()) {
                    items(rows, itemId = { it.noteId.value.hashCode().toLong() }) { row ->
                        PageCell(row, data.thumbs[row.noteId], thumbH, p)
                    }
                }
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

class NoteGridWidgetReceiver : WidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = NoteGridWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val config = WidgetListConfig(context)
        appWidgetIds.forEach(config::clear)
        val styles = WidgetStyleStore(context)
        appWidgetIds.forEach(styles::clear)
        super.onDeleted(context, appWidgetIds)
    }
}

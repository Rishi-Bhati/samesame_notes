package org.samesame.notes.feature.widgets

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.flow.first
import org.samesame.notes.core.model.NoteId
import kotlin.math.abs
import dev.oneuiproject.oneui.R as Oui

/**
 * One note on the home screen: its title and date, then the top of its first page in a rounded frame (drawn dark when the
 * widget's content is dark). Tapping opens the note. A locked or missing note never produces a page or a title.
 */
class SingleNoteWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = WidgetEntryPoint.get(context)
        val manager = GlanceAppWidgetManager(context)
        val widgetId = manager.getAppWidgetId(id)
        val noteId = ep.pins().noteOf(widgetId)
        val note = noteId?.let { runCatching { ep.notes().observeNote(it).first() }.getOrNull() }
        val state = WidgetContent.single(pinned = noteId != null, note = note)
        val style = WidgetStyleStore(context).get(widgetId)
        val wallpaper = wallpaperPrefersDarkText(context)
        val dark = style.isDark(isNight(context), wallpaper)
        // Nothing but a ready note reaches the renderer; the page is cut to each size the launcher shows the widget at.
        val sizes = runCatching { manager.getAppWidgetSizes(id) }.getOrDefault(emptyList()).ifEmpty { listOf(DEFAULT_SIZE) }
        val pages: Map<DpSize, ImageProvider?> = if (state is SingleNoteState.Ready) {
            sizes.distinct().take(2).associateWith { size -> renderFor(context, ep, state.noteId, dark, size)?.let(::ImageProvider) }
        } else emptyMap()
        provideContent { SingleNoteContent(widgetId, state, pages, widgetPalette(style, wallpaper)) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val sample = SingleNoteState.Ready(
            NoteId("preview"), context.getString(R.string.widget_sample_title), System.currentTimeMillis(),
            context.getString(R.string.widget_sample_body),
        )
        val page = mapOf(DEFAULT_SIZE to ImageProvider(R.drawable.widget_pv_page))
        provideContent { SingleNoteContent(0, sample, page, widgetPalette(WidgetStyle())) }
    }

    private suspend fun renderFor(context: Context, ep: WidgetEntryPoint, id: NoteId, dark: Boolean, size: DpSize): Bitmap? {
        val area = SingleNoteLayout.pageArea(size) ?: return null
        val density = context.resources.displayMetrics.density
        val widthPx = (area.width.value * density).toInt()
        return WidgetBitmaps.pageTop(ep.notes(), id, dark, widthPx, area.height.value / area.width.value)
    }

    companion object {
        internal val DEFAULT_SIZE = DpSize(300.dp, 170.dp)
    }
}

/** Geometry of the single-note widget (pure, shared by the page cut and the layout). */
object SingleNoteLayout {
    /** Below this height only the title and a line of text fit: no page. */
    val COMPACT_BELOW = 100.dp

    /** The size of the page frame inside a widget of [size], or null for the compact layout. */
    fun pageArea(size: DpSize): DpSize? {
        if (size.height < COMPACT_BELOW) return null
        val w = size.width - WidgetDims.Inset * 2
        val h = size.height - WidgetDims.Inset * 2 - WidgetDims.HeaderHeight - WidgetDims.Gap / 2
        if (w <= 0.dp || h <= 0.dp) return null
        return DpSize(w, h)
    }
}

@Composable
internal fun SingleNoteContent(widgetId: Int, state: SingleNoteState, pages: Map<DpSize, ImageProvider?>, p: WidgetPalette) {
    val context = LocalContext.current
    val size = LocalSize.current
    val compact = size.height < SingleNoteLayout.COMPACT_BELOW
    when (state) {
        is SingleNoteState.Ready -> {
            val open = actionStartActivity(WidgetIntents.openNote(context, state.noteId.value))
            // Exact mode: the page cut for the size closest to the one being drawn.
            val page = pages.entries.minByOrNull { abs(it.key.width.value - size.width.value) + abs(it.key.height.value - size.height.value) }?.value
            WidgetFrame(p, onClick = open) { ReadyNote(state, page, compact, p, open) }
        }
        SingleNoteState.NotChosen -> WidgetFrame(p, onClick = actionStartActivity(NotePickerActivity.intent(context, widgetId))) {
            StateMessage(Oui.drawable.ic_oui_notes, context.getString(R.string.widget_choose_note), context.getString(R.string.widget_choose_note_hint), p, compact)
        }
        SingleNoteState.Locked -> WidgetFrame(p, onClick = actionStartActivity(WidgetIntents.openApp(context))) {
            StateMessage(Oui.drawable.ic_oui_lock, context.getString(R.string.widget_locked_note), context.getString(R.string.widget_locked_hint), p, compact)
        }
        SingleNoteState.Gone -> WidgetFrame(p, onClick = actionStartActivity(NotePickerActivity.intent(context, widgetId))) {
            StateMessage(Oui.drawable.ic_oui_notes, context.getString(R.string.widget_note_gone), context.getString(R.string.widget_note_gone_hint), p, compact)
        }
    }
}

@Composable
private fun ReadyNote(state: SingleNoteState.Ready, page: ImageProvider?, compact: Boolean, p: WidgetPalette, open: Action) {
    val context = LocalContext.current
    val title = state.title.ifBlank { context.getString(R.string.widget_untitled) }
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth().height(WidgetDims.HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, maxLines = 1, modifier = GlanceModifier.defaultWeight().padding(start = 4.dp),
                style = TextStyle(color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(widgetDate(context, state.modifiedAt), maxLines = 1, modifier = GlanceModifier.padding(end = 4.dp), style = TextStyle(color = p.textSecondary, fontSize = 12.sp))
        }
        if (compact) {
            if (state.snippet.isNotEmpty()) {
                Text(state.snippet, maxLines = 1, modifier = GlanceModifier.padding(horizontal = 4.dp), style = TextStyle(color = p.textSecondary, fontSize = 13.sp))
            }
            return@Column
        }
        Spacer(GlanceModifier.height(WidgetDims.Gap / 2))
        if (page != null) {
            Image(
                page, contentDescription = title,
                modifier = GlanceModifier.fillMaxSize().roundedImage().clickable(open), contentScale = ContentScale.Crop,
            )
        } else {
            // The page could not be drawn (or the preview): the note's text on a tile instead.
            Box(modifier = GlanceModifier.fillMaxSize().rounded(p.roles.tile).padding(12.dp), contentAlignment = Alignment.TopStart) {
                Text(
                    state.snippet.ifEmpty { title }, maxLines = 8,
                    style = TextStyle(color = p.text, fontSize = 13.sp),
                )
            }
        }
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

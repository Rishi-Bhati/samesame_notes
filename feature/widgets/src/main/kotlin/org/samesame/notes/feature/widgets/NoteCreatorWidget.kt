package org.samesame.notes.feature.widgets

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
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
 * Starts a new note in one tap. Three layouts by size: two round buttons (text, pen) when small, a row of five when one cell
 * high, and on taller widgets a header with the app, a wide "write a note" bar and four labelled tiles.
 */
class NoteCreatorWidget : LoggingGlanceWidget() {
    // Exact: the layout and the button size follow the real size, not a bucket.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        WidgetLog.i("creator #$widgetId provideGlance")
        val icon = AppBranding.icon(context, 64)
        val name = AppBranding.name(context)
        provideContent {
            val version by WidgetSignals.version.collectAsState()
            val style = remember(version) { WidgetStyleStore(context).get(widgetId) }
            val wallpaper = remember(version) { wallpaperPrefersDarkText(context) }
            NoteCreatorContent(widgetPalette(style, wallpaper), name, icon)
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val icon = AppBranding.icon(context, 64)
        val name = AppBranding.name(context)
        provideContent { NoteCreatorContent(widgetPalette(WidgetStyle()), name, icon) }
    }
}

/** The creator's layouts, smallest first; each is used from its [size] up. */
enum class CreatorLayout(val size: DpSize) {
    Small(DpSize(110.dp, 40.dp)),
    Strip(DpSize(220.dp, 40.dp)),
    Tall(DpSize(220.dp, 110.dp)),
    Full(DpSize(220.dp, 170.dp)),
    ;

    companion object {
        /** The largest layout that fits [size] (the smallest one when nothing fits). */
        fun forSize(size: DpSize): CreatorLayout =
            entries.lastOrNull { size.width >= it.size.width && size.height >= it.size.height } ?: Small
    }
}

private data class CreatorButton(val action: StartAction, val icon: Int, val description: Int, val label: Int)

private val creatorButtons = listOf(
    CreatorButton(StartAction.Keypad, Oui.drawable.ic_oui_keyboard, R.string.widget_creator_keypad, R.string.widget_creator_label_type),
    CreatorButton(StartAction.Pen, Oui.drawable.ic_oui_pen_fountain, R.string.widget_creator_pen, R.string.widget_creator_label_pen),
    CreatorButton(StartAction.Brush, Oui.drawable.ic_oui_brush_outline, R.string.widget_creator_brush, R.string.widget_creator_label_brush),
    CreatorButton(StartAction.Image, Oui.drawable.ic_oui_image_outline, R.string.widget_creator_image, R.string.widget_creator_label_image),
    CreatorButton(StartAction.Voice, Oui.drawable.ic_oui_voice, R.string.widget_creator_voice, R.string.widget_creator_label_voice),
)

@Composable
internal fun NoteCreatorContent(p: WidgetPalette, appName: String, appIcon: Bitmap?) {
    when (CreatorLayout.forSize(LocalSize.current)) {
        CreatorLayout.Small -> WidgetFrame(p, padding = 6.dp) { ButtonRow(p, creatorButtons.take(2)) }
        CreatorLayout.Strip -> WidgetFrame(p, padding = 6.dp) { ButtonRow(p, creatorButtons) }
        CreatorLayout.Tall -> WidgetFrame(p) {
            Column(GlanceModifier.fillMaxSize()) {
                AppHeader(p, appName, appIcon)
                Spacer(GlanceModifier.height(4.dp))
                ButtonRow(p, creatorButtons, GlanceModifier.fillMaxWidth().defaultWeight())
            }
        }
        CreatorLayout.Full -> WidgetFrame(p) {
            Column(GlanceModifier.fillMaxSize()) {
                AppHeader(p, appName, appIcon)
                Spacer(GlanceModifier.height(WidgetDims.Gap))
                WriteBar(p, GlanceModifier.fillMaxWidth().defaultWeight())
                Spacer(GlanceModifier.height(WidgetDims.Gap))
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    creatorButtons.drop(1).forEachIndexed { i, b ->
                        if (i > 0) Spacer(GlanceModifier.width(WidgetDims.Gap))
                        LabelledTile(p, b, GlanceModifier.defaultWeight().fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(p: WidgetPalette, appName: String, appIcon: Bitmap?) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(WidgetDims.HeaderHeight).clickable(actionStartActivity(WidgetIntents.openApp(context))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(GlanceModifier.width(4.dp))
        if (appIcon != null) {
            Image(ImageProvider(appIcon), contentDescription = null, modifier = GlanceModifier.size(20.dp))
            Spacer(GlanceModifier.width(8.dp))
        }
        Text(appName, maxLines = 1, style = TextStyle(color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold))
    }
}

/** Evenly spread round buttons; the first (new text note) is the accent one. */
@Composable
private fun ButtonRow(p: WidgetPalette, buttons: List<CreatorButton>, modifier: GlanceModifier = GlanceModifier.fillMaxSize()) {
    val context = LocalContext.current
    // 44 dp targets, smaller only on a very low widget (one short cell).
    val buttonSize = (LocalSize.current.height - 12.dp).coerceIn(32.dp, 44.dp)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        buttons.forEachIndexed { i, b ->
            Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight(), contentAlignment = Alignment.Center) {
                RoundButton(
                    icon = b.icon, description = context.getString(b.description),
                    action = actionStartActivity(WidgetIntents.createNote(context, b.action)), p = p,
                    primary = i == 0, size = buttonSize, iconSize = buttonSize / 2,
                )
            }
        }
    }
}

/** The accent "write a note" bar of the full layout: the most common action gets the biggest target. */
@Composable
private fun WriteBar(p: WidgetPalette, modifier: GlanceModifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier.rounded(p.roles.accentTile).clickable(actionStartActivity(WidgetIntents.createNote(context, StartAction.Keypad)))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            ImageProvider(Oui.drawable.ic_oui_keyboard), contentDescription = null,
            modifier = GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(p.accent),
        )
        Spacer(GlanceModifier.width(10.dp))
        Text(
            context.getString(R.string.widget_creator_write), maxLines = 1,
            style = TextStyle(color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun LabelledTile(p: WidgetPalette, b: CreatorButton, modifier: GlanceModifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier.rounded(p.roles.tile).clickable(actionStartActivity(WidgetIntents.createNote(context, b.action))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            ImageProvider(b.icon), contentDescription = context.getString(b.description),
            modifier = GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(p.icon),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(context.getString(b.label), maxLines = 1, style = TextStyle(color = p.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium))
    }
}

class NoteCreatorWidgetReceiver : WidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = NoteCreatorWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val styles = WidgetStyleStore(context)
        appWidgetIds.forEach(styles::clear)
        super.onDeleted(context, appWidgetIds)
    }
}

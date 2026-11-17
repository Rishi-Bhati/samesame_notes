package org.samesame.notes.feature.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
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
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.compose.ui.unit.sp
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import dev.oneuiproject.oneui.R as Oui

/**
 * The reference layout (phone portrait): a 35 dp title row (app name, 14 sp bold, 18 dp side margins, 13 dp above, 4 dp below), then an
 * inset rounded panel (9 dp side and bottom margins, 8 dp top) with six equal cells holding 28 dp icons: the app icon (opens the app), then
 * keyboard, pen, brush, image, voice (each starts a new note in that mode).
 */
class NoteCreatorWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val style = WidgetStyleStore(context).get(GlanceAppWidgetManager(context).getAppWidgetId(id))
        val icon = AppBranding.icon(context, 96)
        val name = AppBranding.name(context)
        provideContent { NoteCreatorContent(widgetPalette(style), name, icon) }
    }
}

private data class CreatorButton(val action: StartAction, val icon: Int, val label: Int)

private val creatorButtons = listOf(
    CreatorButton(StartAction.Keypad, Oui.drawable.ic_oui_keyboard, R.string.widget_creator_keypad),
    CreatorButton(StartAction.Pen, Oui.drawable.ic_oui_pen_fountain, R.string.widget_creator_pen),
    CreatorButton(StartAction.Brush, Oui.drawable.ic_oui_brush_outline, R.string.widget_creator_brush),
    CreatorButton(StartAction.Image, Oui.drawable.ic_oui_image_outline, R.string.widget_creator_image),
    CreatorButton(StartAction.Voice, R.drawable.ic_widget_mic, R.string.widget_creator_voice),
)

@Composable
private fun NoteCreatorContent(p: WidgetPalette, appName: String, appIcon: android.graphics.Bitmap?) {
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(p.surface)) {
        Box(modifier = GlanceModifier.fillMaxWidth().height(35.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                appName, maxLines = 1,
                modifier = GlanceModifier.padding(start = 18.dp, end = 18.dp, top = 13.dp, bottom = 4.dp),
                style = TextStyle(color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
        }
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(start = 9.dp, end = 9.dp, top = 8.dp, bottom = 9.dp)
                .cornerRadius(16.dp).background(p.inset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight().clickable(actionStartActivity(WidgetIntents.openApp(context))),
                contentAlignment = Alignment.Center,
            ) {
                if (appIcon != null) {
                    Image(ImageProvider(appIcon), contentDescription = appName, modifier = GlanceModifier.size(28.dp))
                }
            }
            creatorButtons.forEach { button ->
                Box(
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight().clickable(actionStartActivity(WidgetIntents.createNote(context, button.action))),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        ImageProvider(button.icon), contentDescription = context.getString(button.label),
                        modifier = GlanceModifier.size(28.dp), colorFilter = ColorFilter.tint(p.icon),
                    )
                }
            }
        }
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

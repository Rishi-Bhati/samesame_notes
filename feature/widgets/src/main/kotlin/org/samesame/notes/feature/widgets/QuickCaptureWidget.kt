package org.samesame.notes.feature.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * Quick capture: a big "Take a note" bar that opens a small capture sheet over the home screen ([CaptureActivity]: type or
 * dictate, Save, done; no full app), plus one-tap checklist, dictation, handwriting and photo. Every target is at least
 * 48 dp and labelled for screen readers. Low widgets show the bar with round buttons beside it; taller ones add labelled
 * tiles under the bar.
 */
class QuickCaptureWidget : LoggingGlanceWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        WidgetLog.i("capture #$widgetId provideGlance")
        provideContent {
            val version by WidgetSignals.version.collectAsState()
            val style = remember(version) { WidgetStyleStore(context).get(widgetId) }
            val wallpaper = remember(version) { wallpaperPrefersDarkText(context) }
            QuickCaptureContent(widgetPalette(style, wallpaper))
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { QuickCaptureContent(widgetPalette(WidgetStyle())) }
    }
}

/** Geometry of the quick-capture widget (pure). */
object QuickCaptureLayout {
    /** From this height the labelled tiles appear under the bar. */
    val TILES_FROM_HEIGHT = 120.dp

    /** The bar keeps at least this width (room for its icon and "Take a note"). */
    val MIN_BAR = 150.dp
    val BUTTON = 48.dp
    val BUTTON_GAP = 6.dp

    fun tiles(size: DpSize): Boolean = size.height >= TILES_FROM_HEIGHT

    /** How many of the four round actions fit beside the bar on a low widget. */
    fun sideButtons(width: Dp): Int {
        val room = width - WidgetDims.Inset * 2 - MIN_BAR
        return (room.value / (BUTTON.value + BUTTON_GAP.value)).toInt().coerceIn(0, 4)
    }
}

private data class CaptureAction(val icon: Int, val description: Int, val label: Int, val intent: (Context) -> Intent)

private val captureActions = listOf(
    CaptureAction(Oui.drawable.ic_oui_checkbox_checked_outline, R.string.widget_capture_new_checklist, R.string.widget_capture_label_checklist) {
        CaptureActivity.intent(it, CaptureMode.Checklist)
    },
    CaptureAction(Oui.drawable.ic_oui_voice, R.string.widget_capture_dictate_note, R.string.widget_capture_label_voice) {
        CaptureActivity.intent(it, CaptureMode.Dictate)
    },
    CaptureAction(Oui.drawable.ic_oui_pen_fountain, R.string.widget_creator_pen, R.string.widget_creator_label_pen) {
        WidgetIntents.createNote(it, StartAction.Pen)
    },
    CaptureAction(Oui.drawable.ic_oui_image_outline, R.string.widget_creator_image, R.string.widget_creator_label_image) {
        WidgetIntents.createNote(it, StartAction.Image)
    },
)

@Composable
internal fun QuickCaptureContent(p: WidgetPalette) {
    val size = LocalSize.current
    if (QuickCaptureLayout.tiles(size)) {
        WidgetFrame(p) {
            Column(GlanceModifier.fillMaxSize()) {
                TakeNoteBar(p, GlanceModifier.fillMaxWidth().height(56.dp))
                Spacer(GlanceModifier.height(WidgetDims.Gap))
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    captureActions.forEachIndexed { i, a ->
                        if (i > 0) Spacer(GlanceModifier.width(WidgetDims.Gap))
                        ActionTile(p, a, GlanceModifier.defaultWeight().fillMaxHeight())
                    }
                }
            }
        }
    } else {
        WidgetFrame(p, padding = 8.dp) {
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                TakeNoteBar(p, GlanceModifier.defaultWeight().fillMaxHeight())
                captureActions.take(QuickCaptureLayout.sideButtons(size.width)).forEach { a ->
                    Spacer(GlanceModifier.width(QuickCaptureLayout.BUTTON_GAP))
                    val context = LocalContext.current
                    RoundButton(
                        a.icon, context.getString(a.description), actionStartActivity(a.intent(context)), p,
                        size = QuickCaptureLayout.BUTTON, iconSize = 24.dp,
                    )
                }
            }
        }
    }
}

/** The main target: looks like an input, opens the capture sheet ready to type. */
@Composable
private fun TakeNoteBar(p: WidgetPalette, modifier: GlanceModifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier.rounded(p.roles.tile, radius = 28.dp)
            .clickable(actionStartActivity(CaptureActivity.intent(context, CaptureMode.Text))).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = GlanceModifier.size(36.dp).rounded(p.roles.accent, radius = 18.dp), contentAlignment = Alignment.Center) {
            Image(
                ImageProvider(Oui.drawable.ic_oui_edit_outline), contentDescription = null,
                modifier = GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(p.onAccent),
            )
        }
        Spacer(GlanceModifier.width(10.dp))
        Text(
            context.getString(R.string.widget_capture_bar), maxLines = 1,
            style = TextStyle(color = p.textSecondary, fontSize = 16.sp, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun ActionTile(p: WidgetPalette, a: CaptureAction, modifier: GlanceModifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier.rounded(p.roles.tile).clickable(actionStartActivity(a.intent(context))).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            ImageProvider(a.icon), contentDescription = context.getString(a.description),
            modifier = GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(p.icon),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(context.getString(a.label), maxLines = 1, style = TextStyle(color = p.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium))
    }
}

class QuickCaptureWidgetReceiver : WidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = QuickCaptureWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val styles = WidgetStyleStore(context)
        appWidgetIds.forEach(styles::clear)
        super.onDeleted(context, appWidgetIds)
    }
}

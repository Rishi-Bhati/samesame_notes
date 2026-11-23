package org.samesame.notes.feature.widgets

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle

/**
 * Quick capture: a main action that opens a small capture sheet over the home screen ([CaptureActivity]: type or dictate,
 * Save, done; no full app), plus one-tap actions (checklist, dictation, handwriting, camera by default; the user picks them
 * in the settings, [CaptureButtons]). Every button is labelled for screen readers.
 *
 * There is no card: the widget is one rounded shape sliced into buttons by thin gaps, the main action in the accent. The
 * shape follows the size from 1 x 1 up ([QuickCaptureLayout.spec]): a tile with the accent coin, a connected bar, a capsule,
 * a pad of four quadrants around the coin, or a bento of a large main tile and labelled action tiles. The shape is drawn as
 * tinted alpha masks ([CaptureMasks]) under transparent Glance buttons.
 */
class QuickCaptureWidget : LoggingGlanceWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        WidgetLog.i("capture #$widgetId provideGlance")
        provideContent {
            val version by WidgetSignals.version.collectAsState()
            val style = remember(version) { WidgetStyleStore(context).get(widgetId) }
            val buttons = remember(version) { CaptureButtonStore(context).get(widgetId) }
            val wallpaper = remember(version) { wallpaperPrefersDarkText(context) }
            QuickCaptureContent(widgetPalette(style, wallpaper), buttons)
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { QuickCaptureContent(widgetPalette(WidgetStyle()), CaptureButtons()) }
    }
}

@Composable
internal fun QuickCaptureContent(p: WidgetPalette, buttons: CaptureButtons) {
    val size = LocalSize.current
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val outer = remember { launcherRadius(context) }
    val spec = remember(size, buttons, outer) { QuickCaptureLayout.spec(size, buttons, outer) }
    val surfaceAlpha = p.roles.surface.day.alpha
    val layers = remember(spec, density, surfaceAlpha) { CaptureMasks.render(spec, density, surfaceAlpha) }
    Box(GlanceModifier.fillMaxSize()) {
        layers.surface?.let { MaskLayer(it, CaptureMasks.opaque(p.roles.surface)) }
        layers.wash?.let { MaskLayer(it, CaptureMasks.washOver(p.roles)) }
        layers.accent?.let { MaskLayer(it, p.roles.accent) }
        spec.slices.forEach { slice ->
            val action = if (slice.main) buttons.centre else buttons.corners[slice.slot]
            SliceButton(p, spec, slice, action)
        }
        val coin = spec.coin
        if (coin != null && spec.coinIsButton) CoinButton(p, coin, buttons.centre)
    }
}

/** The launcher's widget corner radius in dp (Android 12+), else [QuickCaptureLayout.OUTER_RADIUS]. */
internal fun launcherRadius(context: Context): Float {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return QuickCaptureLayout.OUTER_RADIUS
    val px = runCatching { context.resources.getDimension(android.R.dimen.system_app_widget_background_radius) }.getOrNull()
        ?: return QuickCaptureLayout.OUTER_RADIUS
    return px / context.resources.displayMetrics.density
}

@Composable
private fun MaskLayer(mask: Bitmap, color: RolePair) {
    Image(
        ImageProvider(mask), contentDescription = null, modifier = GlanceModifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(ColorProvider(day = color.day, night = color.night)),
    )
}

/** Puts [content] at ([x], [y]) dp of the enclosing box (Glance has no offsets: a full-size box padded to the spot). */
@Composable
private fun Place(x: Float, y: Float, content: @Composable () -> Unit) {
    Box(GlanceModifier.fillMaxSize().padding(start = x.coerceAtLeast(0f).dp, top = y.coerceAtLeast(0f).dp)) { content() }
}

/** One slice of the shape as a transparent button over the mask: its icon and, when there is room, its label. */
@Composable
private fun SliceButton(p: WidgetPalette, spec: CaptureSpec, slice: Slice, action: CaptureButton) {
    val context = LocalContext.current
    val intent = action.intent(context) ?: return
    val onShape = slice.main && spec.mainFill != MainFill.Wash
    val iconTint = when {
        slice.main && spec.mainFill == MainFill.Accent -> p.onAccent
        slice.main -> p.onAccent // on the coin
        else -> p.icon
    }
    val icon = if (slice.main) QuickCaptureLayout.mainIcon(action) else action.icon
    Place(slice.x, slice.y) {
        Box(
            GlanceModifier.size(slice.w.dp, slice.h.dp).roundedImage(slice.corners.largest.dp).clickable(actionStartActivity(intent)),
        ) {
            val g = slice.glyph
            Place(g.cx - g.size / 2, g.cy - g.size / 2) {
                Image(
                    ImageProvider(icon), contentDescription = context.getString(action.description),
                    modifier = GlanceModifier.size(g.size.dp), colorFilter = ColorFilter.tint(iconTint),
                )
            }
            slice.label?.let { box ->
                val text = context.getString(
                    when {
                        !slice.main -> action.label
                        action == CaptureButton.NewNote -> R.string.widget_capture_bar
                        else -> action.title
                    },
                )
                val color = when {
                    onShape -> p.onAccent
                    slice.main -> p.accent
                    else -> p.icon
                }
                val style = when {
                    spec.shape == CaptureShape.Row -> TextStyle(color = color, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    slice.main -> TextStyle(color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    else -> TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
                Place(box.x, box.y) {
                    Box(
                        GlanceModifier.size(box.w.dp, box.h.dp),
                        contentAlignment = if (box.start) Alignment.CenterStart else Alignment.Center,
                    ) { Text(text, maxLines = 1, style = style) }
                }
            }
        }
    }
}

/** The pad's main button: the accent coin with its moat as the tap target. */
@Composable
private fun CoinButton(p: WidgetPalette, coin: Coin, main: CaptureButton) {
    val context = LocalContext.current
    val intent = main.intent(context) ?: return
    val target = coin.r + coin.moat
    Place(coin.cx - target, coin.cy - target) {
        Box(
            GlanceModifier.size((target * 2).dp).roundedImage(target.dp).clickable(actionStartActivity(intent)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                ImageProvider(QuickCaptureLayout.mainIcon(main)), contentDescription = context.getString(main.description),
                modifier = GlanceModifier.size(coin.glyph.dp), colorFilter = ColorFilter.tint(p.onAccent),
            )
        }
    }
}

class QuickCaptureWidgetReceiver : WidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = QuickCaptureWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val styles = WidgetStyleStore(context)
        val buttons = CaptureButtonStore(context)
        appWidgetIds.forEach { styles.clear(it); buttons.clear(it) }
        super.onDeleted(context, appWidgetIds)
    }
}

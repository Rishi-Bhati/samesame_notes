package org.samesame.notes.feature.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
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
import kotlin.math.sqrt

/**
 * Quick capture: a big "Take a note" bar that opens a small capture sheet over the home screen ([CaptureActivity]: type or
 * dictate, Save, done; no full app), plus one-tap actions (checklist, dictation, handwriting, camera by default; the user
 * picks them in the settings, [CaptureButtons]). Every target is at least 48 dp and labelled for screen readers. Low widgets
 * show the bar with round buttons beside it, taller ones add labelled tiles under the bar, and a roughly square widget shows
 * one big main button with the four actions in its corners ([QuickCaptureLayout.forSize]).
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

/** The quick-capture layouts. */
enum class CaptureShape {
    /** Low: the bar, with round buttons beside it when there is room. */
    Bar,

    /** Tall (or wide and tall but not square): the bar with labelled tiles under it. */
    Tiles,

    /** Roughly square: the main button in the middle, the four actions in the corners. */
    Square,
}

/** Geometry of the quick-capture widget (pure). */
object QuickCaptureLayout {
    /** From this height the labelled tiles appear under the bar. */
    val TILES_FROM_HEIGHT = 120.dp

    /**
     * The square layout needs both sides from this size: four 48 dp corner buttons and a main button of at least
     * [CENTRE_MIN] must fit without touching. A smaller widget keeps the bar.
     */
    val SQUARE_FROM = 140.dp
    /** Width / height range of the square layout: 3:4 to 4:3 (both included). */
    const val SQUARE_MIN_ASPECT = 3f / 4f
    const val SQUARE_MAX_ASPECT = 4f / 3f

    /** The bar keeps at least this width (room for its icon and "Take a note"). */
    val MIN_BAR = 150.dp
    val BUTTON = 48.dp
    val BUTTON_GAP = 6.dp

    /** Square layout: inset from the widget edge, corner buttons from 48 dp (tap target) to [CORNER_MAX], main button. */
    private const val ASPECT_SLACK = 1e-4f
    val SQUARE_PADDING_MIN = 6.dp
    val SQUARE_PADDING_MAX = 14.dp

    /** Inset of the square layout: 6 dp at the smallest square (room for the main button), growing to 14 dp. */
    fun squarePadding(size: DpSize): Dp =
        (SQUARE_PADDING_MIN + (min(size.width, size.height) - SQUARE_FROM) * 0.1f).coerceIn(SQUARE_PADDING_MIN, SQUARE_PADDING_MAX)
    val CORNER_MAX = 64.dp
    val CENTRE_MIN = 56.dp
    val CENTRE_MAX = 120.dp
    private val CENTRE_GAP = 4.dp

    fun forSize(size: DpSize): CaptureShape = when {
        square(size) -> CaptureShape.Square
        size.height >= TILES_FROM_HEIGHT -> CaptureShape.Tiles
        else -> CaptureShape.Bar
    }

    fun square(size: DpSize): Boolean {
        if (size.width < SQUARE_FROM || size.height < SQUARE_FROM) return false
        val aspect = size.width.value / size.height.value
        return aspect in (SQUARE_MIN_ASPECT - ASPECT_SLACK)..(SQUARE_MAX_ASPECT + ASPECT_SLACK)
    }

    fun tiles(size: DpSize): Boolean = forSize(size) == CaptureShape.Tiles

    /** How many of the four round actions fit beside the bar on a low widget. */
    fun sideButtons(width: Dp): Int {
        val room = width - WidgetDims.Inset * 2 - MIN_BAR
        return (room.value / (BUTTON.value + BUTTON_GAP.value)).toInt().coerceIn(0, 4)
    }

    /** Side of each corner button of the square layout: 30% of the short side, 48 dp to [CORNER_MAX]. */
    fun corner(size: DpSize): Dp = (min(size.width, size.height) * 0.3f).coerceIn(BUTTON, CORNER_MAX)

    /**
     * Diameter of the square layout's main button: as big as it can be without touching the corner buttons (their centres
     * sit on the diagonals), between [CENTRE_MIN] and [CENTRE_MAX].
     */
    fun centre(size: DpSize): Dp {
        val c = corner(size).value
        val pad = squarePadding(size).value
        val dx = (size.width.value - pad * 2 - c) / 2
        val dy = (size.height.value - pad * 2 - c) / 2
        val radius = sqrt(dx * dx + dy * dy) - c / 2 - CENTRE_GAP.value
        return (radius * 2).dp.coerceIn(CENTRE_MIN, CENTRE_MAX)
    }
}

@Composable
internal fun QuickCaptureContent(p: WidgetPalette, buttons: CaptureButtons) {
    val size = LocalSize.current
    val context = LocalContext.current
    when (QuickCaptureLayout.forSize(size)) {
        CaptureShape.Square -> WidgetFrame(p, padding = QuickCaptureLayout.squarePadding(size)) { SquareButtons(p, buttons, size) }
        CaptureShape.Tiles -> WidgetFrame(p) {
            Column(GlanceModifier.fillMaxSize()) {
                val shown = buttons.shown
                if (shown.isEmpty()) {
                    TakeNoteBar(p, buttons.centre, GlanceModifier.fillMaxSize())
                    return@Column
                }
                TakeNoteBar(p, buttons.centre, GlanceModifier.fillMaxWidth().height(56.dp))
                Spacer(GlanceModifier.height(WidgetDims.Gap))
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    shown.forEachIndexed { i, b ->
                        if (i > 0) Spacer(GlanceModifier.width(WidgetDims.Gap))
                        ActionTile(p, b, GlanceModifier.defaultWeight().fillMaxHeight())
                    }
                }
            }
        }
        CaptureShape.Bar -> WidgetFrame(p, padding = 8.dp) {
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                TakeNoteBar(p, buttons.centre, GlanceModifier.defaultWeight().fillMaxHeight())
                buttons.shown.take(QuickCaptureLayout.sideButtons(size.width)).forEach { b ->
                    val intent = b.intent(context) ?: return@forEach
                    Spacer(GlanceModifier.width(QuickCaptureLayout.BUTTON_GAP))
                    RoundButton(
                        b.icon, context.getString(b.description), actionStartActivity(intent), p,
                        size = QuickCaptureLayout.BUTTON, iconSize = 24.dp,
                    )
                }
            }
        }
    }
}

/** The square layout: the four corners (an empty slot stays empty), the big accent main button on top in the middle. */
@Composable
private fun SquareButtons(p: WidgetPalette, buttons: CaptureButtons, size: DpSize) {
    val context = LocalContext.current
    val corner = QuickCaptureLayout.corner(size)
    val centre = QuickCaptureLayout.centre(size)

    @Composable
    fun cornerButton(b: CaptureButton) {
        val intent = b.intent(context)
        if (intent == null) {
            Spacer(GlanceModifier.size(corner))
        } else {
            RoundButton(b.icon, context.getString(b.description), actionStartActivity(intent), p, size = corner, iconSize = corner * 0.5f)
        }
    }

    @Composable
    fun cornerRow(left: CaptureButton, right: CaptureButton) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            cornerButton(left)
            Spacer(GlanceModifier.defaultWeight())
            cornerButton(right)
        }
    }
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(GlanceModifier.fillMaxSize()) {
            cornerRow(buttons.corners[CaptureButtons.TOP_LEFT], buttons.corners[CaptureButtons.TOP_RIGHT])
            Spacer(GlanceModifier.defaultWeight())
            cornerRow(buttons.corners[CaptureButtons.BOTTOM_LEFT], buttons.corners[CaptureButtons.BOTTOM_RIGHT])
        }
        // Drawn last, so it lies over the empty inner corners of the corner buttons' square bounds.
        val main = buttons.centre
        main.intent(context)?.let { intent ->
            RoundButton(
                icon = if (main == CaptureButton.NewNote) Oui.drawable.ic_oui_add else main.icon,
                description = context.getString(main.description), action = actionStartActivity(intent), p = p,
                primary = true, size = centre, iconSize = centre * 0.42f,
            )
        }
    }
}

/** The main target: looks like an input; opens the capture sheet ready to type, or starts the chosen main action. */
@Composable
private fun TakeNoteBar(p: WidgetPalette, action: CaptureButton, modifier: GlanceModifier) {
    val context = LocalContext.current
    val intent = action.intent(context) ?: CaptureActivity.intent(context, CaptureMode.Text)
    val typed = action == CaptureButton.NewNote
    Row(
        modifier = modifier.rounded(p.roles.tile, radius = 28.dp).clickable(actionStartActivity(intent)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = GlanceModifier.size(36.dp).rounded(p.roles.accent, radius = 18.dp), contentAlignment = Alignment.Center) {
            Image(
                ImageProvider(action.icon), contentDescription = null,
                modifier = GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(p.onAccent),
            )
        }
        Spacer(GlanceModifier.width(10.dp))
        Text(
            context.getString(if (typed) R.string.widget_capture_bar else action.title), maxLines = 1,
            style = TextStyle(color = p.textSecondary, fontSize = 16.sp, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun ActionTile(p: WidgetPalette, b: CaptureButton, modifier: GlanceModifier) {
    val context = LocalContext.current
    val intent = b.intent(context) ?: return
    Column(
        modifier = modifier.rounded(p.roles.tile).clickable(actionStartActivity(intent)).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            ImageProvider(b.icon), contentDescription = context.getString(b.description),
            modifier = GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(p.icon),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(context.getString(b.label), maxLines = 1, style = TextStyle(color = p.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium))
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

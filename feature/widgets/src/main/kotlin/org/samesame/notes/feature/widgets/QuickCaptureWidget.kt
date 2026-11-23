package org.samesame.notes.feature.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
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
import androidx.glance.unit.ColorProvider as GlanceColor
import dev.oneuiproject.oneui.R as Oui

/**
 * Quick capture: a big "Take a note" bar that opens a small capture sheet over the home screen ([CaptureActivity]: type or
 * dictate, Save, done; no full app), plus one-tap actions (checklist, dictation, handwriting, camera by default; the user
 * picks them in the settings, [CaptureButtons]). Every target is at least 48 dp and labelled for screen readers. Low widgets
 * show the bar with round buttons beside it, taller ones add labelled tiles under the bar, and a roughly square widget shows
 * the main button in the middle with the four actions around it ([QuickCaptureLayout.forSize], [SquareGeometry]).
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

    /** Roughly square: the main button in the middle, the four actions around it on the diagonals of a 3 x 3 grid. */
    Square,
}

/**
 * Geometry of the square layout for one widget size (all in dp, pure). The five buttons form one centred cluster: the
 * corners sit on the diagonals of a 3 x 3 grid [pitch] away from the middle on both axes, so the cluster stays square on a
 * slightly oblong widget and never spreads into the card's corners on a big one.
 */
data class SquareGeometry(
    /** Side of a corner button's visible tonal circle. */
    val corner: Dp,
    /** Side of the main button's visible rounded square (a little bigger than a corner, never a giant disc). */
    val centre: Dp,
    /** Corner radius of the main button's rounded square. */
    val centreRadius: Dp,
    /** Least gap between a corner button's visible circle and the card edge. */
    val inset: Dp,
    /** Distance on each axis from the middle of the widget to a corner button's centre. */
    val pitch: Dp,
    /** Tap targets (at least 48 dp; larger than the visible shape on a small widget). */
    val cornerTarget: Dp,
    val centreTarget: Dp,
    /** Offset of the corner tap targets from the card's left/right and top/bottom edges. */
    val edgeX: Dp,
    val edgeY: Dp,
    val cornerIcon: Dp,
    val centreIcon: Dp,
)

/** Geometry of the quick-capture widget (pure). */
object QuickCaptureLayout {
    /** From this height the labelled tiles appear under the bar. */
    val TILES_FROM_HEIGHT = 120.dp

    /** The square layout needs both sides from this size (five 48 dp targets that don't overlap). A smaller widget keeps the bar. */
    val SQUARE_FROM = 140.dp
    /** Width / height range of the square layout: 3:4 to 4:3 (both included). */
    const val SQUARE_MIN_ASPECT = 3f / 4f
    const val SQUARE_MAX_ASPECT = 4f / 3f
    private const val ASPECT_SLACK = 1e-4f

    /** The bar keeps at least this width (room for its icon and "Take a note"). */
    val MIN_BAR = 150.dp
    /** Height of the bar and side of its round buttons on a widget tall enough (a lower one shrinks both, see [barHeight]). */
    val BUTTON = 48.dp
    val BUTTON_GAP = 8.dp
    private val BAR_MIN = 32.dp
    /** The least room kept above and below the bar. */
    private val BAR_MARGIN = 8.dp

    /** The least tap target of every button. */
    val TARGET = 48.dp

    /** Square layout: corner circle 25% of the short side within [CORNER_MIN]..[CORNER_MAX]; main button [CENTRE_RATIO] times that. */
    val CORNER_MIN = 36.dp
    val CORNER_MAX = 52.dp
    const val CENTRE_RATIO = 1.3f
    /** Rounding of the main button: a soft rounded square, not a circle. */
    const val CENTRE_RADIUS_RATIO = 0.32f
    /** Least gap between the visible corner circles and the card edge: 9% of the short side within these. */
    val INSET_MIN = 10.dp
    val INSET_MAX = 20.dp
    /** On a big widget the cluster stops spreading: [pitch][SquareGeometry.pitch] is at most this times the corner. */
    const val MAX_PITCH_RATIO = 1.6f

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
        return ((room.value + BUTTON_GAP.value) / (BUTTON.value + BUTTON_GAP.value)).toInt().coerceIn(0, 4)
    }

    /**
     * Columns of the labelled tiles under the bar: one row on a wide widget; two columns when the tile area is taller than half
     * its width (a narrow, tall widget), so the tiles stay roughly square instead of thin slabs.
     */
    fun tileColumns(size: DpSize, count: Int): Int {
        if (count <= 2) return count
        val areaWidth = size.width - WidgetDims.Inset * 2
        val areaHeight = size.height - WidgetDims.Inset * 2 - BUTTON - WidgetDims.Gap
        return if (areaHeight > areaWidth / 2) 2 else count
    }

    /** Height of the bar (and its round buttons) on a widget [height] high: 48 dp, less on a very low widget. */
    fun barHeight(height: Dp): Dp = (height - BAR_MARGIN * 2).coerceIn(BAR_MIN, BUTTON)

    fun squareGeometry(size: DpSize): SquareGeometry {
        val short = min(size.width, size.height)
        val corner = (short * 0.25f).coerceIn(CORNER_MIN, CORNER_MAX)
        val centre = corner * CENTRE_RATIO
        val inset = (short * 0.09f).coerceIn(INSET_MIN, INSET_MAX)
        val pitch = min((short - inset * 2 - corner) / 2, corner * MAX_PITCH_RATIO)
        val cornerTarget = max(TARGET, corner)
        return SquareGeometry(
            corner = corner,
            centre = centre,
            centreRadius = centre * CENTRE_RADIUS_RATIO,
            inset = inset,
            pitch = pitch,
            cornerTarget = cornerTarget,
            centreTarget = max(TARGET, centre),
            edgeX = size.width / 2 - pitch - cornerTarget / 2,
            edgeY = size.height / 2 - pitch - cornerTarget / 2,
            cornerIcon = (corner * 0.5f).coerceIn(20.dp, 24.dp),
            centreIcon = (centre * 0.4f).coerceIn(24.dp, 28.dp),
        )
    }
}

@Composable
internal fun QuickCaptureContent(p: WidgetPalette, buttons: CaptureButtons) {
    val size = LocalSize.current
    val context = LocalContext.current
    when (QuickCaptureLayout.forSize(size)) {
        CaptureShape.Square -> WidgetFrame(p, padding = 0.dp) { SquareButtons(p, buttons, QuickCaptureLayout.squareGeometry(size)) }
        CaptureShape.Tiles -> WidgetFrame(p) {
            Column(GlanceModifier.fillMaxSize()) {
                val shown = buttons.shown
                if (shown.isEmpty()) {
                    TakeNoteBar(p, buttons.centre, GlanceModifier.fillMaxSize(), radius = null)
                    return@Column
                }
                TakeNoteBar(p, buttons.centre, GlanceModifier.fillMaxWidth().height(QuickCaptureLayout.BUTTON), radius = QuickCaptureLayout.BUTTON / 2)
                Spacer(GlanceModifier.height(WidgetDims.Gap))
                val rows = shown.chunked(QuickCaptureLayout.tileColumns(size, shown.size))
                rows.forEachIndexed { r, row ->
                    if (r > 0) Spacer(GlanceModifier.height(WidgetDims.Gap))
                    Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                        row.forEachIndexed { i, b ->
                            if (i > 0) Spacer(GlanceModifier.width(WidgetDims.Gap))
                            ActionTile(p, b, GlanceModifier.defaultWeight().fillMaxHeight())
                        }
                    }
                }
            }
        }
        CaptureShape.Bar -> WidgetFrame(p, padding = 0.dp) {
            val h = QuickCaptureLayout.barHeight(size.height)
            Row(
                GlanceModifier.fillMaxSize().padding(horizontal = WidgetDims.Inset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TakeNoteBar(p, buttons.centre, GlanceModifier.defaultWeight().height(h), radius = h / 2)
                buttons.shown.take(QuickCaptureLayout.sideButtons(size.width)).forEach { b ->
                    val intent = b.intent(context) ?: return@forEach
                    Spacer(GlanceModifier.width(QuickCaptureLayout.BUTTON_GAP))
                    RoundButton(
                        b.icon, context.getString(b.description), actionStartActivity(intent), p,
                        size = h, iconSize = if (h < QuickCaptureLayout.BUTTON) h / 2 else 22.dp,
                    )
                }
            }
        }
    }
}

/**
 * The square layout: four soft tonal circles around a slightly bigger rounded square in a soft accent wash. An empty corner
 * keeps its place, so the cluster stays balanced.
 */
@Composable
private fun SquareButtons(p: WidgetPalette, buttons: CaptureButtons, g: SquareGeometry) {
    val context = LocalContext.current

    @Composable
    fun cornerButton(b: CaptureButton) {
        val intent = b.intent(context)
        if (intent == null) {
            Spacer(GlanceModifier.size(g.cornerTarget))
        } else {
            CaptureTarget(
                b.icon, context.getString(b.description), actionStartActivity(intent), target = g.cornerTarget, visible = g.corner,
                radius = g.corner / 2, iconSize = g.cornerIcon, fill = p.roles.tile, tint = p.icon,
            )
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
        Column(GlanceModifier.fillMaxSize().padding(horizontal = g.edgeX, vertical = g.edgeY)) {
            cornerRow(buttons.corners[CaptureButtons.TOP_LEFT], buttons.corners[CaptureButtons.TOP_RIGHT])
            Spacer(GlanceModifier.defaultWeight())
            cornerRow(buttons.corners[CaptureButtons.BOTTOM_LEFT], buttons.corners[CaptureButtons.BOTTOM_RIGHT])
        }
        val main = buttons.centre
        main.intent(context)?.let { intent ->
            CaptureTarget(
                icon = if (main == CaptureButton.NewNote) Oui.drawable.ic_oui_add else main.icon,
                description = context.getString(main.description), action = actionStartActivity(intent),
                target = g.centreTarget, visible = g.centre, radius = g.centreRadius, iconSize = g.centreIcon,
                fill = p.roles.accentTile, tint = p.accent,
            )
        }
    }
}

/** A tap target of [target] (at least 48 dp) holding a visible [visible]-sized rounded shape with the icon. */
@Composable
private fun CaptureTarget(
    icon: Int,
    description: String,
    action: Action,
    target: Dp,
    visible: Dp,
    radius: Dp,
    iconSize: Dp,
    fill: RolePair,
    tint: GlanceColor,
) {
    Box(
        modifier = GlanceModifier.size(target).roundedImage(radius + (target - visible) / 2).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = GlanceModifier.size(visible).rounded(fill, radius = radius), contentAlignment = Alignment.Center) {
            Image(
                ImageProvider(icon), contentDescription = description, modifier = GlanceModifier.size(iconSize),
                colorFilter = ColorFilter.tint(tint),
            )
        }
    }
}

/**
 * The main target: a soft accent pill with the action's icon and "Take a note"; opens the capture sheet ready to type, or
 * starts the chosen main action. [radius] null = the system's inner radius (a pill that fills a tall widget).
 */
@Composable
private fun TakeNoteBar(p: WidgetPalette, action: CaptureButton, modifier: GlanceModifier, radius: Dp?) {
    val context = LocalContext.current
    val intent = action.intent(context) ?: CaptureActivity.intent(context, CaptureMode.Text)
    val typed = action == CaptureButton.NewNote
    Row(
        modifier = modifier.rounded(p.roles.accentTile, radius = radius).clickable(actionStartActivity(intent)).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            ImageProvider(action.icon), contentDescription = null,
            modifier = GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(p.accent),
        )
        Spacer(GlanceModifier.width(10.dp))
        Text(
            context.getString(if (typed) R.string.widget_capture_bar else action.title), maxLines = 1,
            style = TextStyle(color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium),
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
            modifier = GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(p.icon),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(context.getString(b.label), maxLines = 1, style = TextStyle(color = p.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium))
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

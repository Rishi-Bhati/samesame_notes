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
 * picks them in the settings, [CaptureButtons]). Every button is labelled for screen readers. The layout follows the size
 * from 1 x 1 up ([QuickCaptureLayout.forSize]): one main button, a row or a column of buttons, the bar, the bar with tiles, or
 * on an about square widget a square card with the main button in the middle and the four actions around it.
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

/** The quick-capture layouts, chosen from the widget's size alone ([QuickCaptureLayout.forSize]). */
enum class CaptureShape {
    /** Small and about square (1 x 1), or nothing else fits: the main button alone on a square card. */
    Single,

    /** Low and narrow: a row of round buttons, the main one first, evenly spread. */
    Icons,

    /** Low and wide: the "Take a note" bar, with round buttons beside it when there is room. */
    Bar,

    /** Narrow and tall (1 x 2, 1 x 3, ...): a column of round buttons, the main one on top, evenly spread. */
    Column,

    /** About square and big enough (2 x 2, 3 x 3): a square card with the main button in the middle and four around it. */
    Square,

    /** Big and clearly oblong (3 x 2, 4 x 2, 2 x 4, ...): the bar with labelled tiles under it. */
    Tiles,
}

/**
 * Geometry of the square layout for one widget size (all in dp, pure). The card is a true square of [side] (the widget's
 * shorter side) centred in the widget's area; the rest of the area stays transparent. The five buttons form one centred
 * cluster in it: the corners sit on the diagonals of a 3 x 3 grid [pitch] away from the middle, and never spread into the
 * card's corners on a big one.
 */
data class SquareGeometry(
    /** Side of the square card: the shorter side of the widget. */
    val side: Dp,
    /** Side of a corner button's visible tonal circle. */
    val corner: Dp,
    /** Side of the main button's visible rounded square (a little bigger than a corner, never a giant disc). */
    val centre: Dp,
    /** Corner radius of the main button's rounded square. */
    val centreRadius: Dp,
    /** Least gap between a corner button's visible circle and the card edge. */
    val inset: Dp,
    /** Distance on each axis from the middle of the card to a corner button's centre. */
    val pitch: Dp,
    /** Tap targets (at least 48 dp; larger than the visible shape on a small widget). */
    val cornerTarget: Dp,
    val centreTarget: Dp,
    /** Offset of the corner tap targets from the square card's edges (the same on all four sides). */
    val edge: Dp,
    val cornerIcon: Dp,
    val centreIcon: Dp,
)

/** Geometry of the single-button layout: a square card of [side] with the main button ([button], [radius]) in its middle. */
data class SingleGeometry(val side: Dp, val button: Dp, val radius: Dp, val target: Dp, val icon: Dp)

/** Geometry of the quick-capture widget (pure). */
object QuickCaptureLayout {
    /** From this height a wide widget shows the labelled tiles under the bar. */
    val TILES_FROM_HEIGHT = 120.dp

    /** About square from this side up: the five-button square layout (smaller: the single main button). */
    val SQUARE_FROM = 120.dp
    /** Width / height range that counts as about square: 2:3 to 3:2 (both included). */
    const val SQUARE_MIN_ASPECT = 2f / 3f
    const val SQUARE_MAX_ASPECT = 3f / 2f
    private const val ASPECT_SLACK = 1e-4f

    /** A tall widget narrower than this is a column of buttons; a wider one gets the bar and tiles. */
    val COLUMN_BELOW = 160.dp

    /** The bar keeps at least this width (room for its icon and "Take a note"). */
    val MIN_BAR = 150.dp
    /** Below this width a low widget drops the label and shows round buttons only. */
    val BAR_LABEL_FROM = WidgetDims.Inset * 2 + MIN_BAR
    /** Height of the bar and side of its round buttons on a widget big enough (a smaller one shrinks both). */
    val BUTTON = 48.dp
    val BUTTON_GAP = 8.dp
    private val BUTTON_MIN = 32.dp
    /** The least room kept around a row or column of buttons, across it. */
    private val BAR_MARGIN = 8.dp

    /** The least tap target of every button. */
    val TARGET = 48.dp

    /** Square layout: corner circle 25% of the side within [CORNER_MIN]..[CORNER_MAX]; main button [CENTRE_RATIO] times that. */
    val CORNER_MIN = 32.dp
    val CORNER_MAX = 52.dp
    const val CENTRE_RATIO = 1.3f
    /** Rounding of the main button: a soft rounded square, not a circle. */
    const val CENTRE_RADIUS_RATIO = 0.32f
    /** Least gap between the visible corner circles and the card edge: 8% of the side within these. */
    val INSET_MIN = 8.dp
    val INSET_MAX = 20.dp
    /** On a big widget the cluster stops spreading: [pitch][SquareGeometry.pitch] is at most this times the corner. */
    const val MAX_PITCH_RATIO = 1.6f

    /**
     * The layout for [size], given how many corner [actions] are set. About square: the square cluster, or the single
     * button when small. Wide: tiles when tall enough, else the bar (or icons only when too narrow for its label). Tall:
     * a column when narrow, else tiles. A row or column with no room for any action shows the single button instead.
     */
    fun forSize(size: DpSize, actions: Int = CaptureButtons.SLOTS): CaptureShape {
        if (nearSquare(size)) return if (min(size.width, size.height) >= SQUARE_FROM) CaptureShape.Square else CaptureShape.Single
        return if (size.width > size.height) {
            when {
                size.height >= TILES_FROM_HEIGHT -> CaptureShape.Tiles
                size.width >= BAR_LABEL_FROM -> CaptureShape.Bar
                minOf(rowActions(size), actions) == 0 -> CaptureShape.Single
                else -> CaptureShape.Icons
            }
        } else {
            when {
                size.width >= COLUMN_BELOW -> CaptureShape.Tiles
                minOf(columnActions(size), actions) == 0 -> CaptureShape.Single
                else -> CaptureShape.Column
            }
        }
    }

    fun nearSquare(size: DpSize): Boolean {
        val aspect = size.width.value / size.height.value
        return aspect in (SQUARE_MIN_ASPECT - ASPECT_SLACK)..(SQUARE_MAX_ASPECT + ASPECT_SLACK)
    }

    fun square(size: DpSize): Boolean = forSize(size) == CaptureShape.Square

    fun tiles(size: DpSize): Boolean = forSize(size) == CaptureShape.Tiles

    /** How many of the four round actions fit beside the bar on a low widget. */
    fun sideButtons(width: Dp): Int {
        val room = width - WidgetDims.Inset * 2 - MIN_BAR
        return ((room.value + BUTTON_GAP.value) / (BUTTON.value + BUTTON_GAP.value)).toInt().coerceIn(0, 4)
    }

    /** Height of the bar (and side of the round buttons in a row) on a widget [height] high: 48 dp, less on a very low one. */
    fun barHeight(height: Dp): Dp = (height - BAR_MARGIN * 2).coerceIn(BUTTON_MIN, BUTTON)

    /** Side of the round buttons in a column on a widget [width] wide. */
    fun columnButton(width: Dp): Dp = (width - BAR_MARGIN * 2).coerceIn(BUTTON_MIN, BUTTON)

    /** How many actions fit after the main button in the icon row (each button with a gap, inside the inset). */
    fun rowActions(size: DpSize): Int = fit(size.width, barHeight(size.height))

    /** How many actions fit under the main button in the column. */
    fun columnActions(size: DpSize): Int = fit(size.height, columnButton(size.width))

    private fun fit(length: Dp, button: Dp): Int {
        val room = length - WidgetDims.Inset * 2 + BUTTON_GAP
        return ((room.value / (button.value + BUTTON_GAP.value)).toInt() - 1).coerceIn(0, CaptureButtons.SLOTS)
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

    fun singleGeometry(size: DpSize): SingleGeometry {
        val side = min(size.width, size.height)
        val button = (side * 0.46f).coerceIn(min(side - BAR_MARGIN, 36.dp), 56.dp)
        return SingleGeometry(
            side = side,
            button = button,
            radius = button * CENTRE_RADIUS_RATIO,
            target = max(TARGET, button).coerceAtMost(side),
            icon = (button * 0.46f).coerceIn(20.dp, 28.dp),
        )
    }

    fun squareGeometry(size: DpSize): SquareGeometry {
        val side = min(size.width, size.height)
        val corner = (side * 0.25f).coerceIn(CORNER_MIN, CORNER_MAX)
        val centre = corner * CENTRE_RATIO
        val inset = (side * 0.08f).coerceIn(INSET_MIN, INSET_MAX)
        val pitch = min((side - inset * 2 - corner) / 2, corner * MAX_PITCH_RATIO)
        val cornerTarget = max(TARGET, corner)
        return SquareGeometry(
            side = side,
            corner = corner,
            centre = centre,
            centreRadius = centre * CENTRE_RADIUS_RATIO,
            inset = inset,
            pitch = pitch,
            cornerTarget = cornerTarget,
            centreTarget = max(TARGET, centre),
            edge = side / 2 - pitch - cornerTarget / 2,
            cornerIcon = (corner * 0.5f).coerceIn(18.dp, 24.dp),
            centreIcon = (centre * 0.4f).coerceIn(24.dp, 28.dp),
        )
    }

    /** Icon of the main button when it is shown as a button: "+" for a new note, else the action's own icon. */
    fun mainIcon(action: CaptureButton): Int = if (action == CaptureButton.NewNote) Oui.drawable.ic_oui_add else action.icon
}

@Composable
internal fun QuickCaptureContent(p: WidgetPalette, buttons: CaptureButtons) {
    val size = LocalSize.current
    val context = LocalContext.current
    val shown = buttons.shown
    when (QuickCaptureLayout.forSize(size, shown.size)) {
        CaptureShape.Single -> {
            val g = QuickCaptureLayout.singleGeometry(size)
            SquareCard(p, g.side) {
                Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MainButton(p, buttons.centre, target = g.target, visible = g.button, radius = g.radius, iconSize = g.icon)
                }
            }
        }
        CaptureShape.Square -> {
            val g = QuickCaptureLayout.squareGeometry(size)
            SquareCard(p, g.side) { SquareButtons(p, buttons, g) }
        }
        CaptureShape.Icons -> WidgetFrame(p, padding = 0.dp) {
            val b = QuickCaptureLayout.barHeight(size.height)
            Row(GlanceModifier.fillMaxSize().padding(horizontal = WidgetDims.Inset), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) { MainButton(p, buttons.centre, b, b, b * CENTRE_RADIUS, b / 2) }
                shown.take(QuickCaptureLayout.rowActions(size)).forEach { action ->
                    Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) { ActionButton(p, action, b) }
                }
            }
        }
        CaptureShape.Column -> WidgetFrame(p, padding = 0.dp) {
            val b = QuickCaptureLayout.columnButton(size.width)
            Column(GlanceModifier.fillMaxSize().padding(vertical = WidgetDims.Inset), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) { MainButton(p, buttons.centre, b, b, b * CENTRE_RADIUS, b / 2) }
                shown.take(QuickCaptureLayout.columnActions(size)).forEach { action ->
                    Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) { ActionButton(p, action, b) }
                }
            }
        }
        CaptureShape.Tiles -> WidgetFrame(p) {
            Column(GlanceModifier.fillMaxSize()) {
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
                shown.take(QuickCaptureLayout.sideButtons(size.width)).forEach { b ->
                    Spacer(GlanceModifier.width(QuickCaptureLayout.BUTTON_GAP))
                    ActionButton(p, b, h)
                }
            }
        }
    }
}

private const val CENTRE_RADIUS = QuickCaptureLayout.CENTRE_RADIUS_RATIO

/** A true square card of [side] centred in the widget's cells; the rest of the area stays transparent and not clickable. */
@Composable
private fun SquareCard(p: WidgetPalette, side: Dp, content: @Composable () -> Unit) {
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(GlanceModifier.size(side).rounded(p.roles.surface, outer = true)) { content() }
    }
}

/** The main action as a button: a rounded square in the soft accent wash with the accent icon. */
@Composable
private fun MainButton(p: WidgetPalette, main: CaptureButton, target: Dp, visible: Dp, radius: Dp, iconSize: Dp) {
    val context = LocalContext.current
    val intent = main.intent(context) ?: return
    CaptureTarget(
        icon = QuickCaptureLayout.mainIcon(main), description = context.getString(main.description), action = actionStartActivity(intent),
        target = target, visible = visible, radius = radius, iconSize = iconSize, fill = p.roles.accentTile, tint = p.accent,
    )
}

/** One corner action as a soft tonal circle of [side]. */
@Composable
private fun ActionButton(p: WidgetPalette, b: CaptureButton, side: Dp) {
    val context = LocalContext.current
    val intent = b.intent(context) ?: return
    RoundButton(
        b.icon, context.getString(b.description), actionStartActivity(intent), p,
        size = side, iconSize = if (side < QuickCaptureLayout.BUTTON) side / 2 else 22.dp,
    )
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
        Column(GlanceModifier.fillMaxSize().padding(g.edge)) {
            cornerRow(buttons.corners[CaptureButtons.TOP_LEFT], buttons.corners[CaptureButtons.TOP_RIGHT])
            Spacer(GlanceModifier.defaultWeight())
            cornerRow(buttons.corners[CaptureButtons.BOTTOM_LEFT], buttons.corners[CaptureButtons.BOTTOM_RIGHT])
        }
        val main = buttons.centre
        main.intent(context)?.let { intent ->
            CaptureTarget(
                icon = QuickCaptureLayout.mainIcon(main),
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

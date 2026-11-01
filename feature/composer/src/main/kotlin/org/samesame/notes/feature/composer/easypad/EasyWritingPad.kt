package org.samesame.notes.feature.composer.easypad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke as InkStroke
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.fixedSp
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.tools.randomElementId
import org.samesame.notes.feature.composer.R
import kotlin.math.max
import kotlin.math.min

/** Total pad height on the reference phone (#79): 39 dp header + 144.6 dp writing area. */
private val HeaderHeight = 39.dp
private val DefaultAreaHeight = 144.6.dp

private class PadStroke(val points: List<Offset>)

private class PlacedGroup(val pageId: String, val ids: Set<ElementId>, val startX: Float)

/**
 * Easy writing pad (#79): a full-width panel above the toolbar. Header: drag grip (drag to resize), previous "<", next ">",
 * enter. Below, a writing area magnifying the cursor's text line about 4x: handwriting written here is scaled down and placed
 * on the text cursor's line (see [EasyPadMath]). The faint line near the right edge is where a group is committed on its own.
 * Guide text shows until the pad has been used once ([guideVisible], then [onGuideSeen]).
 *
 * [slot] is the text cursor's line; [slotKey] changes whenever the cursor moves (the pad then restarts from the slot).
 */
@Composable
internal fun EasyWritingPad(
    slot: PadSlot?,
    slotKey: Any,
    pageIdOf: (Int) -> String?,
    brush: Brush,
    guideVisible: Boolean,
    onGuideSeen: () -> Unit,
    onAdd: (pageId: String, element: InkElement) -> Unit,
    onRemove: (pageId: String, ids: Set<ElementId>) -> Unit,
    onEnter: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val dark = SameSameTheme.colors.isDark
    var areaHeight by remember { mutableStateOf(DefaultAreaHeight) }
    var padWidthPx by remember { mutableStateOf(0f) }
    val areaHeightPx = with(density) { areaHeight.toPx() }

    // Where the next group starts; restarts from the text cursor when it moves.
    var x by remember(slotKey) { mutableStateOf(slot?.x ?: 0f) }
    var shown by remember(slotKey) { mutableStateOf(emptyList<PadStroke>()) }
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var drawing by remember { mutableStateOf(false) }
    var strokeCount by remember { mutableIntStateOf(0) }
    // Pending (uncommitted) group.
    var anchorPadX by remember(slotKey) { mutableStateOf<Float?>(null) }
    var groupMinX by remember(slotKey) { mutableStateOf(0f) }
    var groupMaxX by remember(slotKey) { mutableStateOf(0f) }
    var pendingIds by remember(slotKey) { mutableStateOf(emptyList<ElementId>()) }
    var pendingPage by remember(slotKey) { mutableStateOf<String?>(null) }
    var pendingStartX by remember(slotKey) { mutableStateOf(0f) }
    var committed by remember(slotKey) { mutableStateOf(emptyList<PlacedGroup>()) }

    val currentSlot = slot?.copy(x = x)
    val scale = if (currentSlot != null) EasyPadMath.scale(areaHeightPx, currentSlot.lineHeight) else 1f

    fun commit(forWrap: Boolean = true) {
        val s = currentSlot ?: return
        val page = pendingPage
        if (page != null && pendingIds.isNotEmpty()) {
            committed = committed + PlacedGroup(page, pendingIds.toSet(), pendingStartX)
            val width = (groupMaxX - groupMinX) / scale
            x = EasyPadMath.advance(s.copy(x = pendingStartX), width)
            onGuideSeen()
        }
        pendingIds = emptyList(); pendingPage = null; anchorPadX = null; shown = emptyList()
        if (forWrap && EasyPadMath.needsWrap(x, s)) onEnter()
    }

    fun previous() {
        val page = pendingPage
        if (page != null && pendingIds.isNotEmpty()) {
            onRemove(page, pendingIds.toSet())
            x = pendingStartX
            pendingIds = emptyList(); pendingPage = null; anchorPadX = null; shown = emptyList()
        } else if (committed.isNotEmpty()) {
            val g = committed.last()
            onRemove(g.pageId, g.ids)
            committed = committed.dropLast(1)
            x = g.startX
        }
    }

    fun next() {
        val s = currentSlot ?: return
        if (pendingIds.isNotEmpty()) commit() else {
            x += EasyPadMath.spaceWidth(s)
            if (EasyPadMath.needsWrap(x, s)) onEnter()
        }
    }

    fun finishStroke(points: List<Offset>, times: List<Long>, pressures: List<Float>, tool: InputToolType) {
        val s = currentSlot ?: return
        val pageId = pageIdOf(s.pageIndex) ?: return
        if (points.size < 2) return
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        if (anchorPadX == null) {
            anchorPadX = minX; groupMinX = minX; groupMaxX = maxX; pendingStartX = s.x; pendingPage = pageId
        } else {
            groupMinX = min(groupMinX, minX); groupMaxX = max(groupMaxX, maxX)
        }
        val anchor = anchorPadX ?: minX
        val batch = MutableStrokeInputBatch()
        for (i in points.indices) {
            val p = EasyPadMath.toPage(points[i].x, points[i].y, anchor, s.copy(x = pendingStartX), scale)
            batch.add(tool, p.x, p.y, (times[i] - times[0]).coerceAtLeast(0), pressure = pressures[i])
        }
        val element = InkElement(randomElementId(), InkStroke(brush, batch))
        onAdd(pageId, element)
        pendingIds = pendingIds + element.id
        shown = shown + PadStroke(points)
        strokeCount++
        if (padWidthPx > 0f && maxX > padWidthPx * EasyPadMath.COMMIT_FRACTION) commit()
    }

    // Idle commit.
    LaunchedEffect(strokeCount, drawing) {
        if (!drawing && pendingIds.isNotEmpty()) {
            kotlinx.coroutines.delay(EasyPadMath.IDLE_COMMIT_MS)
            commit()
        }
    }

    val line = if (dark) Color(0xFF3A3A3A) else Color(0xFFD0D0D0)
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, line),
    ) {
        Column {
            // Header (#79: grip at 26 dp, "<" ">" enter centred 39 dp apart, last one 32 dp from the right).
            Row(Modifier.fillMaxWidth().height(HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.padding(start = 14.dp).size(24.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { change, drag ->
                                change.consume()
                                areaHeight = (areaHeight - drag.toDp()).coerceIn(90.dp, 380.dp)
                            }
                        }
                        .semantics { contentDescription = "Move handle" },
                    contentAlignment = Alignment.Center,
                ) { Icon(AppIcons.Handle, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface) }
                Box(Modifier.weight(1f))
                PadButton(AppIcons.ChevronLeft, stringResource(R.string.feature_composer_easy_pad_previous), ::previous)
                PadButton(AppIcons.ChevronRight, stringResource(R.string.feature_composer_easy_pad_next), ::next)
                PadButton(AppIcons.EnterKey, stringResource(R.string.feature_composer_easy_pad_enter)) { commit(forWrap = false); onEnter() }
                Box(Modifier.width(7.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(line))
            // Writing area.
            Box(
                Modifier.fillMaxWidth().height(areaHeight).clip(RoundedCornerShape(0.dp))
                    .pointerInput(currentSlot != null, scale, brush) {
                        if (currentSlot == null) return@pointerInput
                        padWidthPx = size.width.toFloat()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            drawing = true
                            val tool = if (down.type == PointerType.Stylus) InputToolType.STYLUS else InputToolType.TOUCH
                            val pts = mutableListOf(down.position)
                            val times = mutableListOf(down.uptimeMillis)
                            val pressures = mutableListOf(down.pressure.takeIf { it > 0f } ?: 0.5f)
                            live = pts.toList()
                            while (true) {
                                val event = awaitPointerEvent()
                                val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                pts += ch.position; times += ch.uptimeMillis
                                pressures += ch.pressure.takeIf { it > 0f } ?: 0.5f
                                live = pts.toList()
                                ch.consume()
                            }
                            drawing = false
                            live = emptyList()
                            finishStroke(pts, times, pressures, tool)
                        }
                    },
            ) {
                val ink = Color(brush.colorIntArgb)
                val guide = MaterialTheme.colorScheme.onSurface
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height
                    // Right-hand commit zone (#79: lighter band right of the marker) and marker line with its pin.
                    val mx = w * EasyPadMath.COMMIT_FRACTION
                    drawRect(guide.copy(alpha = 0.06f), Offset(mx, 0f), androidx.compose.ui.geometry.Size(w - mx, h))
                    drawLine(Color(0xFFFF6A47), Offset(mx, 0f), Offset(mx, h), 1.dp.toPx())
                    val pin = Path().apply {
                        moveTo(mx - 7.dp.toPx(), 0f); lineTo(mx + 7.dp.toPx(), 0f)
                        lineTo(mx + 7.dp.toPx(), 9.dp.toPx()); lineTo(mx, 15.dp.toPx()); lineTo(mx - 7.dp.toPx(), 9.dp.toPx()); close()
                    }
                    drawPath(pin, Color(0xFFFF6A47))
                    // Baseline.
                    val by = EasyPadMath.baselineY(h)
                    drawLine(guide.copy(alpha = 0.25f), Offset(0f, by), Offset(mx, by), 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
                    val widthPx = (brush.size * scale).coerceIn(1.5f.dp.toPx(), 14.dp.toPx())
                    fun draw(points: List<Offset>) {
                        if (points.size < 2) { points.firstOrNull()?.let { drawCircle(ink, widthPx / 2, it) }; return }
                        val p = Path().apply { moveTo(points[0].x, points[0].y); for (i in 1 until points.size) lineTo(points[i].x, points[i].y) }
                        drawPath(p, ink, style = Stroke(widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    shown.forEach { draw(it.points) }
                    draw(live)
                }
                if (guideVisible && shown.isEmpty() && live.isEmpty()) {
                    Text(
                        stringResource(R.string.feature_composer_easy_pad_guide),
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        fontSize = fixedSp(16f), textAlign = TextAlign.Center, maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun PadButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(39.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface) }
}

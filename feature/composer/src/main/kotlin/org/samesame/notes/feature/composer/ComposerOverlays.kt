package org.samesame.notes.feature.composer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.samesame.notes.core.model.document.Affine2
import org.samesame.notes.core.model.document.ObjectFrame
import org.samesame.notes.core.model.document.transformedBy
import org.samesame.notes.feature.composer.canvas.DocumentLayout
import org.samesame.notes.feature.composer.canvas.drawObjectChrome
import org.samesame.notes.feature.composer.canvas.Viewport
import org.samesame.notes.feature.composer.input.ToolOverlay
import org.samesame.notes.feature.composer.panels.ComposerColors
import org.samesame.notes.feature.composer.session.SelectionState
import org.samesame.notes.feature.composer.session.tools.SessionToolHandlers

/** Lasso path, rectangle, eraser circle and the ink selection box, drawn in view space over the canvas. */
@Composable
internal fun ToolOverlayLayer(
    handlers: SessionToolHandlers,
    selectionState: State<SelectionState>,
    pageIds: List<String>,
    layout: DocumentLayout,
    viewport: Viewport,
    dark: Boolean = false,
) {
    val eraser by handlers.eraser.overlay.collectAsState()
    val lasso by handlers.selection.overlay.collectAsState()
    Canvas(Modifier.fillMaxSize()) {
        val color = ComposerColors.selectionBox
        val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))
        val line = Stroke(width = 2.dp.toPx(), pathEffect = dash)
        fun v(p: Offset) = viewport.docToView(p)
        for (o in listOf(eraser, lasso)) {
            when (o) {
                is ToolOverlay.Path -> if (o.points.size >= 2) {
                    val path = Path().apply {
                        moveTo(v(o.points[0]).x, v(o.points[0]).y)
                        for (i in 1 until o.points.size) v(o.points[i]).let { lineTo(it.x, it.y) }
                        if (o.closed) close()
                    }
                    drawPath(path, color, style = line)
                }
                is ToolOverlay.Circle -> drawCircle(
                    androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f), o.radiusPu * viewport.scale, v(o.center),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                is ToolOverlay.Rect -> {
                    val a = v(o.a); val b = v(o.b)
                    drawRect(color, Offset(minOf(a.x, b.x), minOf(a.y, b.y)), androidx.compose.ui.geometry.Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y)), style = line)
                }
                null -> Unit
            }
        }
        // Read the selection in the draw phase: moving a selection only redraws, it doesn't recompose.
        drawSelectionBox(selectionState.value, pageIds, layout, viewport, dark)
    }
}

private fun DrawScope.drawSelectionBox(sel: SelectionState, pageIds: List<String>, layout: DocumentLayout, viewport: Viewport, dark: Boolean) {
    val b = sel.bounds ?: return
    val i = pageIds.indexOf(sel.pageId)
    if (i < 0 || i >= layout.pageCount) return
    val origin = layout.pageOrigin(i)
    sel.frame?.let { f0 ->
        // One object: its own (rotated) frame with the object chrome. The live transform carries the frame while dragging.
        val m = sel.liveTransform
        val f = if (m != null) f0.transformedBy(Affine2(m.m00, m.m10, m.m20, m.m01, m.m11, m.m21)) else f0
        val c = viewport.docToView(Offset(f.cx + origin.x, f.cy + origin.y))
        drawObjectChrome(ObjectFrame(c.x, c.y, f.w * viewport.scale, f.h * viewport.scale, f.rotationDeg), sel.rotatable, dark)
        return
    }
    var r = Rect(b.left + origin.x, b.top + origin.y, b.right + origin.x, b.bottom + origin.y)
    sel.liveTransform?.let { m ->
        // Moves and scales only: x' = m00 x + m20, y' = m11 y + m21 (page space), applied to the page-space box.
        val l = m.m00 * b.left + m.m20; val rr = m.m00 * b.right + m.m20
        val t = m.m11 * b.top + m.m21; val bb = m.m11 * b.bottom + m.m21
        r = Rect(minOf(l, rr) + origin.x, minOf(t, bb) + origin.y, maxOf(l, rr) + origin.x, maxOf(t, bb) + origin.y)
    }
    val tl = viewport.docToView(r.topLeft)
    val br = viewport.docToView(r.bottomRight)
    val color = ComposerColors.selectionBox
    drawRect(color, tl, androidx.compose.ui.geometry.Size(br.x - tl.x, br.y - tl.y), style = Stroke(1.5.dp.toPx()))
    val cx = (tl.x + br.x) / 2; val cy = (tl.y + br.y) / 2
    val handles = listOf(
        tl, Offset(cx, tl.y), Offset(br.x, tl.y), Offset(tl.x, cy), Offset(br.x, cy),
        Offset(tl.x, br.y), Offset(cx, br.y), br,
    )
    for (h in handles) {
        drawCircle(androidx.compose.ui.graphics.Color.White, 6.dp.toPx(), h)
        drawCircle(color, 6.dp.toPx(), h, style = Stroke(1.5.dp.toPx()))
    }
}

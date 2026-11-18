package org.samesame.notes.feature.composer.pdf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.data.pdf.PdfCoordinates
import org.samesame.notes.core.model.pdf.PdfPageText
import org.samesame.notes.core.model.pdf.RectPt
import org.samesame.notes.feature.composer.canvas.DocumentLayout
import org.samesame.notes.feature.composer.canvas.Viewport
import kotlin.math.roundToInt

private val HighlightColor = Color(0x401976D2)
private val HandleColor = Color(0xFF1976D2)
private const val HANDLE_RADIUS_DP = 11f
private const val HANDLE_TOUCH_RADIUS_DP = 28f

/**
 * Compose overlay rendering PDF text selection highlights, teardrop drag handles,
 * Compose magnifier, and floating Copy context bar.
 */
@Composable
fun PdfSelectionOverlay(
    selectionState: PdfSelectionState,
    pageTextProvider: (Int) -> PdfPageText?,
    pageWidthPtProvider: (Int) -> Float,
    layout: DocumentLayout,
    viewport: Viewport,
    modifier: Modifier = Modifier,
) {
    val selectedPageIndex = selectionState.selectedPage ?: return
    val pageText = pageTextProvider(selectedPageIndex) ?: return
    if (!selectionState.hasSelection) return

    val widthPt = pageWidthPtProvider(selectedPageIndex)
    if (widthPt <= 0f) return

    val pageRect = layout.pageRects.getOrNull(selectedPageIndex) ?: return
    val pageOrigin = pageRect.topLeft
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current

    val handleRadiusPx = with(density) { HANDLE_RADIUS_DP.dp.toPx() }
    val touchRadiusPx = with(density) { HANDLE_TOUCH_RADIUS_DP.dp.toPx() }

    val handleTipsPt = remember(selectionState.selectionRange, pageText) {
        selectionState.handleTipsPt(pageText)
    }

    val startTipView = handleTipsPt?.first?.let { pt ->
        val (vx, vy) = PdfCoordinates.ptToView(
            ptX = pt.x,
            ptY = pt.y,
            widthPt = widthPt,
            pageOriginX = pageOrigin.x,
            pageOriginY = pageOrigin.y,
            originX = viewport.origin.x,
            originY = viewport.origin.y,
            scale = viewport.scale,
        )
        Offset(vx, vy)
    }

    val endTipView = handleTipsPt?.second?.let { pt ->
        val (vx, vy) = PdfCoordinates.ptToView(
            ptX = pt.x,
            ptY = pt.y,
            widthPt = widthPt,
            pageOriginX = pageOrigin.x,
            pageOriginY = pageOrigin.y,
            originX = viewport.origin.x,
            originY = viewport.origin.y,
            scale = viewport.scale,
        )
        Offset(vx, vy)
    }

    val selectionRectsPt = remember(selectionState.selectionRange, pageText) {
        selectionState.selectionRectsPt(pageText)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(startTipView, endTipView, viewport.scale, viewport.origin) {
                if (startTipView == null || endTipView == null) return@pointerInput
                detectDragGestures(
                    onDragStart = { pos ->
                        val handle = selectionState.hitTestHandle(pos, startTipView, endTipView, touchRadiusPx)
                        if (handle != null) {
                            selectionState.isDraggingHandle = true
                            selectionState.isStartHandle = handle
                            selectionState.dragPositionPx = pos
                        }
                    },
                    onDrag = { change, dragAmount ->
                        if (selectionState.isDraggingHandle) {
                            change.consume()
                            val newPos = selectionState.dragPositionPx + dragAmount
                            selectionState.dragPositionPx = newPos

                            val (ptX, ptY) = PdfCoordinates.viewToPt(
                                viewX = newPos.x,
                                viewY = newPos.y,
                                originX = viewport.origin.x,
                                originY = viewport.origin.y,
                                scale = viewport.scale,
                                pageOriginX = pageOrigin.x,
                                pageOriginY = pageOrigin.y,
                                widthPt = widthPt,
                            )
                            selectionState.updateDrag(pageText, ptX, ptY)
                        }
                    },
                    onDragEnd = {
                        selectionState.isDraggingHandle = false
                    },
                    onDragCancel = {
                        selectionState.isDraggingHandle = false
                    },
                )
            },
    ) {
        // Draw highlight and teardrop handles
        Canvas(Modifier.fillMaxSize()) {
            // Draw highlight rects
            for (r in selectionRectsPt) {
                val (vx1, vy1) = PdfCoordinates.ptToView(
                    r.left, r.top, widthPt, pageOrigin.x, pageOrigin.y,
                    viewport.origin.x, viewport.origin.y, viewport.scale,
                )
                val (vx2, vy2) = PdfCoordinates.ptToView(
                    r.right, r.bottom, widthPt, pageOrigin.x, pageOrigin.y,
                    viewport.origin.x, viewport.origin.y, viewport.scale,
                )
                drawRect(
                    color = HighlightColor,
                    topLeft = Offset(vx1, vy1),
                    size = Size(vx2 - vx1, vy2 - vy1),
                )
            }

            // Draw start handle (teardrop pointing up-right, knob down-left)
            if (startTipView != null) {
                drawTeardropHandle(
                    tip = startTipView,
                    radiusPx = handleRadiusPx,
                    isStart = true,
                    color = HandleColor,
                )
            }

            // Draw end handle (teardrop pointing up-left, knob down-right)
            if (endTipView != null) {
                drawTeardropHandle(
                    tip = endTipView,
                    radiusPx = handleRadiusPx,
                    isStart = false,
                    color = HandleColor,
                )
            }
        }

        // Magnifier when dragging
        if (selectionState.isDraggingHandle) {
            Box(
                Modifier
                    .size(1.dp)
                    .offset {
                        IntOffset(
                            selectionState.dragPositionPx.x.roundToInt(),
                            selectionState.dragPositionPx.y.roundToInt(),
                        )
                    }
                    .magnifier(
                        sourceCenter = { Offset.Zero },
                        magnifierCenter = { Offset(0f, -with(density) { 60.dp.toPx() }) },
                        size = with(density) { androidx.compose.ui.unit.DpSize(100.dp, 48.dp) },
                        cornerRadius = with(density) { 24.dp },
                    ),
            )
        }

        // Floating "Copy" Context Action Bar
        if (!selectionState.isDraggingHandle && startTipView != null) {
            val menuOffset = IntOffset(
                x = (startTipView.x - with(density) { 40.dp.toPx() }).coerceAtLeast(16f).roundToInt(),
                y = (startTipView.y - with(density) { 64.dp.toPx() }).coerceAtLeast(16f).roundToInt(),
            )

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .offset { menuOffset }
                    .wrapContentSize(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    TextButton(
                        onClick = {
                            selectionState.copyToClipboard(clipboard, pageText)
                            selectionState.clear()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Copy",
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Draws a teardrop selection handle.
 * When [isStart] is true, the knob hangs down and to the left.
 * When [isStart] is false, the knob hangs down and to the right.
 */
private fun DrawScope.drawTeardropHandle(
    tip: Offset,
    radiusPx: Float,
    isStart: Boolean,
    color: Color,
) {
    val handleLengthPx = radiusPx * 1.5f
    val center = if (isStart) {
        Offset(tip.x - radiusPx * 0.7f, tip.y + handleLengthPx)
    } else {
        Offset(tip.x + radiusPx * 0.7f, tip.y + handleLengthPx)
    }

    val path = Path().apply {
        moveTo(tip.x, tip.y)
        if (isStart) {
            lineTo(tip.x, tip.y + radiusPx)
            cubicTo(
                tip.x, tip.y + radiusPx,
                center.x + radiusPx, center.y - radiusPx * 0.5f,
                center.x + radiusPx, center.y,
            )
            arcTo(
                rect = Rect(center.x - radiusPx, center.y - radiusPx, center.x + radiusPx, center.y + radiusPx),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            lineTo(tip.x, tip.y)
        } else {
            lineTo(tip.x, tip.y + radiusPx)
            cubicTo(
                tip.x, tip.y + radiusPx,
                center.x - radiusPx, center.y - radiusPx * 0.5f,
                center.x - radiusPx, center.y,
            )
            arcTo(
                rect = Rect(center.x - radiusPx, center.y - radiusPx, center.x + radiusPx, center.y + radiusPx),
                startAngleDegrees = 180f,
                sweepAngleDegrees = -180f,
                forceMoveTo = false,
            )
            lineTo(tip.x, tip.y)
        }
        close()
    }

    drawPath(path, color)
    drawCircle(color, radiusPx, center)
}

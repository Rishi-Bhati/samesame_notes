package org.samesame.notes.feature.composer.toolbar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Eraser block: rounded slanted rectangle with a lighter tip band. Drawn by us. */
@Composable
fun EraserIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        rotate(-45f, Offset(s / 2, s / 2)) {
            val w = s * 0.78f; val h = s * 0.42f
            val tl = Offset((s - w) / 2, (s - h) / 2)
            drawRoundRect(tint, tl, Size(w, h), CornerRadius(s * 0.1f), style = Stroke(width = s * 0.09f))
            drawLine(tint, Offset(tl.x + w * 0.4f, tl.y), Offset(tl.x + w * 0.4f, tl.y + h), strokeWidth = s * 0.08f)
            drawRoundRect(tint.copy(alpha = 0.5f), tl + Offset(w * 0.4f, 0f), Size(w * 0.6f, h), CornerRadius(s * 0.1f))
        }
    }
}

/** Dashed freeform loop for the lasso tool. */
@Composable
fun LassoIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val p = Path().apply {
            moveTo(s * 0.2f, s * 0.45f)
            cubicTo(s * 0.2f, s * 0.15f, s * 0.7f, s * 0.05f, s * 0.85f, s * 0.35f)
            cubicTo(s * 0.98f, s * 0.6f, s * 0.7f, s * 0.9f, s * 0.4f, s * 0.82f)
            cubicTo(s * 0.15f, s * 0.75f, s * 0.2f, s * 0.6f, s * 0.2f, s * 0.45f)
            close()
        }
        drawPath(p, tint, style = Stroke(width = s * 0.08f, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(s * 0.1f, s * 0.1f))))
    }
}

/** Dashed rounded rectangle for the rectangle selection mode. */
@Composable
fun RectSelectIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        drawRoundRect(tint, Offset(s * 0.14f, s * 0.14f), Size(s * 0.72f, s * 0.72f), CornerRadius(s * 0.1f),
            style = Stroke(width = s * 0.08f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(s * 0.1f, s * 0.1f))))
    }
}

/** Pencil in a square (reading mode "Edit", #95): square open at the top right with a diagonal pencil. Drawn by us. */
@Composable
fun EditSquareIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.085f
        val frame = Path().apply {
            moveTo(s * 0.55f, s * 0.2f)
            lineTo(s * 0.3f, s * 0.2f)
            quadraticTo(s * 0.18f, s * 0.2f, s * 0.18f, s * 0.32f)
            lineTo(s * 0.18f, s * 0.7f)
            quadraticTo(s * 0.18f, s * 0.82f, s * 0.3f, s * 0.82f)
            lineTo(s * 0.68f, s * 0.82f)
            quadraticTo(s * 0.8f, s * 0.82f, s * 0.8f, s * 0.7f)
            lineTo(s * 0.8f, s * 0.45f)
        }
        drawPath(frame, tint, style = Stroke(width = w, cap = StrokeCap.Round))
        // pencil body from the lower left of the sheet to the upper right, with a nib
        drawLine(tint, Offset(s * 0.42f, s * 0.6f), Offset(s * 0.82f, s * 0.2f), strokeWidth = s * 0.17f, cap = StrokeCap.Round)
        drawLine(tint, Offset(s * 0.4f, s * 0.6f), Offset(s * 0.4f, s * 0.6f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

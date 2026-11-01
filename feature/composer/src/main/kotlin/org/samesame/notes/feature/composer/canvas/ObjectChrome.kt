package org.samesame.notes.feature.composer.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import org.samesame.notes.core.model.document.FrameHandle
import org.samesame.notes.core.model.document.ObjectFrame

/**
 * Chrome of a selected / edited floating object: the frame outline, 8 hollow resize handles and (when [rotatable]) the
 * rotate handle on a stem below the bottom edge. [frame] is in view px (rotation about its centre). Sizes follow the
 * reference captures (handle 10 dp across, rotate handle 16 dp across, 25.5 dp beyond the bottom edge) and the rotate
 * icon's own palette (ring #F46A4E, face #FAFAFA); the resize handles are hollow: ring only, filled with the page tone.
 */
internal object ObjectChrome {
    val accent = Color(0xFFF46A4E)
    const val HANDLE_DIAMETER_DP = 10f
    const val ROTATE_DIAMETER_DP = 16f
    const val ROTATE_GAP_DP = 25.5f
    const val LINE_DP = 1f
}

internal fun DrawScope.drawObjectChrome(frame: ObjectFrame, rotatable: Boolean, dark: Boolean, showHandles: Boolean = true) {
    val line = ObjectChrome.LINE_DP.dp.toPx()
    val accent = ObjectChrome.accent
    val cs = frame.corners()
    val outline = Path().apply {
        moveTo(cs[0].x, cs[0].y); lineTo(cs[1].x, cs[1].y); lineTo(cs[2].x, cs[2].y); lineTo(cs[3].x, cs[3].y); close()
    }
    drawPath(outline, accent, style = Stroke(line))
    if (!showHandles) return
    val gap = ObjectChrome.ROTATE_GAP_DP.dp.toPx()
    if (rotatable) {
        val bottom = frame.rotateHandlePoint(0f)
        val c = frame.rotateHandlePoint(gap)
        drawLine(accent, Offset(bottom.x, bottom.y), Offset(c.x, c.y), line)
        drawRotateKnob(Offset(c.x, c.y), frame.rotationDeg)
    }
    val fill = if (dark) Color(0xFF383838) else Color(0xFFFAFAFA)
    val r = ObjectChrome.HANDLE_DIAMETER_DP.dp.toPx() / 2f
    for (h in FrameHandle.entries) {
        val p = frame.handlePoint(h)
        drawCircle(fill, r, Offset(p.x, p.y))
        drawCircle(accent, r, Offset(p.x, p.y), style = Stroke(line))
    }
}

/** The rotate knob: a 16 dp disc with two arrows chasing each other, turned with the object. */
private fun DrawScope.drawRotateKnob(center: Offset, rotationDeg: Float) {
    val d = ObjectChrome.ROTATE_DIAMETER_DP.dp.toPx()
    val u = d / 16f // one unit of the 16 x 16 design grid
    val accent = ObjectChrome.accent
    drawCircle(Color(0xFFFAFAFA), d / 2f - u * 0.25f, center)
    drawCircle(accent, d / 2f - u * 0.25f, center, style = Stroke(u))
    translate(center.x - d / 2f, center.y - d / 2f) {
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        val s = Stroke(width = u, cap = StrokeCap.Butt)
        val left = Path().apply {
            moveTo(6.37f * u, 4.26f * u)
            cubicTo(4.97f * u, 4.88f * u, 4f * u, 6.30f * u, 4f * u, 7.94f * u)
            cubicTo(4f * u, 9.69f * u, 5.11f * u, 11.18f * u, 6.65f * u, 11.74f * u)
            moveTo(4.35f * u, 4f * u); lineTo(6.51f * u, 4f * u); lineTo(6.51f * u, 6.17f * u)
        }
        val right = Path().apply {
            moveTo(9.62f * u, 11.62f * u)
            cubicTo(11.02f * u, 10.99f * u, 11.99f * u, 9.58f * u, 11.99f * u, 7.94f * u)
            cubicTo(11.99f * u, 6.19f * u, 10.89f * u, 4.70f * u, 9.34f * u, 4.14f * u)
            moveTo(11.64f * u, 11.88f * u); lineTo(9.48f * u, 11.88f * u); lineTo(9.48f * u, 9.70f * u)
        }
        drawPath(left, accent, style = s)
        drawPath(right, accent, style = s)
    }
}

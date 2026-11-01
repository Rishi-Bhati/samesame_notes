package org.samesame.notes.feature.composer.session.tools

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.ink.geometry.AffineTransform
import org.samesame.notes.core.ink.tools.StrokeTransforms

enum class ResizeHandle { Move, TL, T, TR, R, BR, B, BL, L }

/**
 * The matrix of a selection transform drag (page space). Pure, so it is unit tested on the JVM.
 * Corners scale both axes about the opposite corner (uniformly when [lockAspect]), edges one axis about the
 * opposite edge, [ResizeHandle.Move] translates. [minSpan] is the smallest width/height the box may shrink to
 * (page units); the box is never flipped.
 */
object SelectionResize {
    const val MIN_SCALE = 0.05f

    fun matrix(h: ResizeHandle, b: Rect, start: Offset, now: Offset, lockAspect: Boolean = false, minSpan: Float = 0f): AffineTransform {
        val d = now - start
        if (h == ResizeHandle.Move) return StrokeTransforms.translate(d.x, d.y)
        fun s(newSpan: Float, span: Float, ): Float {
            if (span < 1f) return 1f
            return maxOf(newSpan / span, MIN_SCALE, minSpan / span)
        }
        var sx = 1f; var sy = 1f; var px = b.left; var py = b.top
        when (h) {
            ResizeHandle.TL, ResizeHandle.L, ResizeHandle.BL -> { sx = s(b.right - now.x, b.width); px = b.right }
            ResizeHandle.TR, ResizeHandle.R, ResizeHandle.BR -> { sx = s(now.x - b.left, b.width); px = b.left }
            else -> Unit
        }
        when (h) {
            ResizeHandle.TL, ResizeHandle.T, ResizeHandle.TR -> { sy = s(b.bottom - now.y, b.height); py = b.bottom }
            ResizeHandle.BL, ResizeHandle.B, ResizeHandle.BR -> { sy = s(now.y - b.top, b.height); py = b.top }
            else -> Unit
        }
        val corner = h == ResizeHandle.TL || h == ResizeHandle.TR || h == ResizeHandle.BL || h == ResizeHandle.BR
        if (lockAspect && corner) {
            val u = maxOf(sx, sy)
            sx = u; sy = u
        }
        return StrokeTransforms.scaleAbout(sx, sy, px, py)
    }

    /** Applies [m] to a point, for tests and the live box. */
    fun apply(m: AffineTransform, p: Offset) = Offset(m.m00 * p.x + m.m10 * p.y + m.m20, m.m01 * p.x + m.m11 * p.y + m.m21)
}

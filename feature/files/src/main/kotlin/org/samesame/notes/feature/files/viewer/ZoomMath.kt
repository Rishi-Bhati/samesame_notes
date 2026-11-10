package org.samesame.notes.feature.files.viewer

import kotlin.math.max

/** Zoom state of a viewer page: [scale] 1 = fitted; offsets are the content centre's shift from the container centre, in px. */
internal data class Zoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    val zoomed: Boolean get() = scale > 1.01f
}

/** Pure pinch / pan maths of the image viewer (JVM-tested). */
internal object ZoomMath {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 6f
    const val DOUBLE_TAP_SCALE = 2.5f

    /** The size of a [w] x [h] image fitted inside a [cw] x [ch] container (never enlarged past the container). */
    fun fit(cw: Float, ch: Float, w: Float, h: Float): Pair<Float, Float> {
        if (cw <= 0f || ch <= 0f || w <= 0f || h <= 0f) return 0f to 0f
        val k = minOf(cw / w, ch / h)
        return w * k to h * k
    }

    /** How far the content centre may move from the container centre on one axis. */
    fun maxOffset(container: Float, fitted: Float, scale: Float): Float = max(0f, (fitted * scale - container) / 2f)

    fun clamp(zoom: Zoom, cw: Float, ch: Float, fw: Float, fh: Float): Zoom {
        val scale = zoom.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val mx = maxOffset(cw, fw, scale)
        val my = maxOffset(ch, fh, scale)
        return Zoom(scale, zoom.x.coerceIn(-mx, mx), zoom.y.coerceIn(-my, my))
    }

    /**
     * Applies a gesture step: zoom by [factor] around the centroid ([cx], [cy], relative to the container centre), then
     * pan by ([panX], [panY]). The point under the centroid stays under it.
     */
    fun transform(zoom: Zoom, factor: Float, cx: Float, cy: Float, panX: Float, panY: Float, cw: Float, ch: Float, fw: Float, fh: Float): Zoom {
        val scale = (zoom.scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val k = scale / zoom.scale
        val x = cx - (cx - zoom.x) * k + panX
        val y = cy - (cy - zoom.y) * k + panY
        return clamp(Zoom(scale, x, y), cw, ch, fw, fh)
    }

    /** Double tap: zoom in around the tapped point, or back to fitted when already zoomed. */
    fun doubleTap(zoom: Zoom, tapX: Float, tapY: Float, cw: Float, ch: Float, fw: Float, fh: Float): Zoom =
        if (zoom.zoomed) Zoom() else transform(zoom, DOUBLE_TAP_SCALE / zoom.scale, tapX, tapY, 0f, 0f, cw, ch, fw, fh)

    /**
     * The power-of-two sample size that brings a [width] x [height] image within [maxPixels] and [maxSide], or 0 when the
     * bounds are not an image's.
     */
    fun sampleSize(width: Int, height: Int, maxPixels: Long, maxSide: Int): Int {
        if (width <= 0 || height <= 0 || maxPixels <= 0 || maxSide <= 0) return 0
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels || width / sample > maxSide || height / sample > maxSide) {
            sample *= 2
            if (sample > 4096) return 0
        }
        return sample
    }
}

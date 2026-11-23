package org.samesame.notes.feature.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The quick-capture shape as paths and alpha masks. Glance can't draw arbitrary shapes, so each colour layer of a
 * [CaptureSpec] (surface, accent wash, accent) becomes an ALPHA_8 bitmap that the widget tints: the colours (day/night)
 * stay live in the system's hands and the mask costs one byte a pixel. The system tints with SRC_ATOP, which ignores a
 * tint's own alpha, so the tints are opaque ([opaque], [washOver]) and the surface opacity is drawn into the mask itself.
 */
internal object CaptureMasks {
    /** Masks above this many pixels are drawn smaller and scaled up (the shape stays smooth at that size). */
    private const val MAX_PIXELS = 1_000_000

    class Layers(val surface: Bitmap?, val wash: Bitmap?, val accent: Bitmap?)

    fun slicePath(s: Slice, scale: Float, into: Path = Path()): Path {
        val c = s.corners
        val radii = floatArrayOf(c.tl, c.tl, c.tr, c.tr, c.br, c.br, c.bl, c.bl).map { it * scale }.toFloatArray()
        into.addRoundRect(RectF(s.x * scale, s.y * scale, (s.x + s.w) * scale, (s.y + s.h) * scale), radii, Path.Direction.CW)
        return into
    }

    /** The surface-coloured slices, with the pad's moat cut out around the coin. */
    fun surfacePath(spec: CaptureSpec, scale: Float): Path? {
        val slices = spec.slices.filter { !(it.main && spec.mainFill == MainFill.Accent) }
        if (slices.isEmpty()) return null
        val path = Path()
        slices.forEach { slicePath(it, scale, path) }
        val coin = spec.coin
        if (coin != null && coin.moat > 0f) {
            val cut = Path().apply { addCircle(coin.cx * scale, coin.cy * scale, (coin.r + coin.moat) * scale, Path.Direction.CW) }
            path.op(cut, Path.Op.DIFFERENCE)
        }
        return path
    }

    fun washPath(spec: CaptureSpec, scale: Float): Path? =
        if (spec.mainFill != MainFill.Wash) null else spec.slices.firstOrNull { it.main }?.let { slicePath(it, scale) }

    /** The main slice when it is painted in the accent, and the coin. */
    fun accentPath(spec: CaptureSpec, scale: Float): Path? {
        val path = Path()
        var any = false
        if (spec.mainFill == MainFill.Accent) spec.slices.firstOrNull { it.main }?.let { slicePath(it, scale, path); any = true }
        spec.coin?.let { path.addCircle(it.cx * scale, it.cy * scale, it.r * scale, Path.Direction.CW); any = true }
        return if (any) path else null
    }

    /** The scale (pixels per dp) the masks are drawn at for [density], kept under [MAX_PIXELS]. */
    fun scaleFor(spec: CaptureSpec, density: Float): Float {
        val px = spec.width * spec.height * density * density
        return if (px <= MAX_PIXELS) density else density * sqrt(MAX_PIXELS / px)
    }

    /** [pair] without its alpha. */
    fun opaque(pair: RolePair) = RolePair(pair.day.copy(alpha = 1f), pair.night.copy(alpha = 1f))

    /** The accent wash ([WidgetColorRoles.accentTile]) laid over the opaque surface, as one opaque colour. */
    fun washOver(roles: WidgetColorRoles): RolePair {
        fun over(top: Color, base: Color): Color {
            val a = top.alpha
            return Color(top.red * a + base.red * (1 - a), top.green * a + base.green * (1 - a), top.blue * a + base.blue * (1 - a), 1f)
        }
        return RolePair(over(roles.accentTile.day, roles.surface.day), over(roles.accentTile.night, roles.surface.night))
    }

    /**
     * The masks for [spec] at [density]. The surface and wash masks carry [surfaceAlpha] (the widget's opacity), the
     * accent mask is solid.
     */
    fun render(spec: CaptureSpec, density: Float, surfaceAlpha: Float = 1f): Layers {
        val scale = scaleFor(spec, density)
        val w = max(1, (spec.width * scale).roundToInt())
        val h = max(1, (spec.height * scale).roundToInt())
        fun mask(path: Path?, alpha: Float = 1f): Bitmap? {
            path ?: return null
            if (alpha <= 0f) return null
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.alpha = (alpha.coerceAtMost(1f) * 255).roundToInt() }
            Canvas(bitmap).drawPath(path, paint)
            return bitmap
        }
        return Layers(
            mask(surfacePath(spec, scale), surfaceAlpha), mask(washPath(spec, scale), surfaceAlpha), mask(accentPath(spec, scale)),
        )
    }
}

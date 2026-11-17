package org.samesame.notes.feature.widgets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.model.NoteId
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object WidgetBitmaps {
    /**
     * Width the list and pages thumbnails are rendered at: the home grid's card width, so the repository's on-disk preview
     * cache is shared with it (a thumbnail the home screen already drew costs nothing here).
     */
    const val THUMB_RENDER_WIDTH_PX = 384

    /** Widest render for the single-note widget (wider looks no sharper on a phone and costs memory). */
    const val PAGE_MAX_WIDTH_PX = 720

    /**
     * The system refuses widget bitmaps beyond 1.5 screens of pixels in total (the whole update is dropped then): one page
     * stays well under it...
     */
    const val MAX_BITMAP_BYTES = 1_500_000

    /**
     * ...and all thumbnails of one list or pages widget together stay under this (each is larger than the binder's in-place
     * limit, so it travels as shared memory, not in the transaction).
     */
    const val THUMB_BUDGET_BYTES = 1_200_000

    /** The note's first page, or null (locked, missing, render failed). The repository never renders a locked note. */
    suspend fun page(notes: NoteRepository, id: NoteId, dark: Boolean, widthPx: Int = THUMB_RENDER_WIDTH_PX): Bitmap? {
        val png = runCatching { notes.renderPreview(id, widthPx, darkTheme = dark) }.getOrNull() ?: return null
        return runCatching { BitmapFactory.decodeByteArray(png, 0, png.size) }.getOrNull()
    }

    /**
     * The top of the note's first page cut to [aspect] (height / width) for the single-note widget: Glance only crops around
     * the centre, but a note is read from the top.
     */
    suspend fun pageTop(notes: NoteRepository, id: NoteId, dark: Boolean, widthPx: Int, aspect: Float): Bitmap? {
        val full = page(notes, id, dark, widthPx.coerceIn(THUMB_RENDER_WIDTH_PX, PAGE_MAX_WIDTH_PX)) ?: return null
        return fit(topCrop(full, aspect))
    }

    /** A [w] x [h] thumbnail of the page's top, on an opaque base (RGB_565 halves the bytes; page renders are opaque). */
    suspend fun thumbnail(notes: NoteRepository, id: NoteId, dark: Boolean, w: Int, h: Int): Bitmap? {
        val full = page(notes, id, dark) ?: return null
        val src = cropRect(full.width, full.height, h.toFloat() / w)
        val out = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.RGB_565)
        val canvas = Canvas(out)
        canvas.drawColor(if (dark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt())
        canvas.drawBitmap(full, Rect(0, 0, src.first, src.second), Rect(0, 0, out.width, out.height), Paint(Paint.FILTER_BITMAP_FLAG))
        full.recycle()
        return out
    }

    fun topCrop(bitmap: Bitmap, aspect: Float): Bitmap {
        val (w, h) = cropRect(bitmap.width, bitmap.height, aspect)
        if (w == bitmap.width && h == bitmap.height) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, w, h)
    }

    /** [bitmap] scaled down until it is within [MAX_BITMAP_BYTES]. */
    fun fit(bitmap: Bitmap): Bitmap {
        val scale = scaleFor(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /** The factor (at most 1) that brings a `width x height` ARGB bitmap under [MAX_BITMAP_BYTES]. */
    fun scaleFor(width: Int, height: Int): Float {
        val bytes = width.toLong() * height * 4
        if (bytes <= MAX_BITMAP_BYTES) return 1f
        return sqrt(MAX_BITMAP_BYTES.toDouble() / bytes).toFloat()
    }

    /**
     * The `width x height` region, from the top-left corner, of a `srcW x srcH` image that has [aspect] (height / width):
     * the full width and the top part when the image is taller; the whole image when it is shorter (a short page is shown
     * whole and the widget centres it).
     */
    fun cropRect(srcW: Int, srcH: Int, aspect: Float): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0 || aspect <= 0f) return srcW.coerceAtLeast(1) to srcH.coerceAtLeast(1)
        val wantH = (srcW * aspect).roundToInt().coerceAtLeast(1)
        return if (wantH <= srcH) srcW to wantH else srcW to srcH
    }

    /**
     * Pixel size of one thumbnail of [aspect] (height / width) when [count] RGB_565 thumbnails share [THUMB_BUDGET_BYTES]:
     * [desiredW] pixels wide when that fits, smaller otherwise (never below 48 px).
     */
    fun thumbSize(count: Int, desiredW: Int, aspect: Float, budget: Int = THUMB_BUDGET_BYTES): Pair<Int, Int> {
        val n = count.coerceAtLeast(1)
        val perThumb = budget / n
        val maxW = sqrt(perThumb / (2.0 * aspect)).toInt()
        val w = minOf(desiredW, maxW).coerceAtLeast(48)
        return w to (w * aspect).roundToInt().coerceAtLeast(1)
    }
}

package org.samesame.notes.feature.widgets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.model.NoteId

internal object WidgetBitmaps {
    /** Width the page preview is rendered at (the repository caches it on disk per revision). */
    private const val RENDER_WIDTH_PX = 480

    /**
     * RemoteViews refuse bitmaps beyond a fraction of the screen's memory (and a binder transaction that is too large
     * drops the whole widget): stay well under it.
     */
    const val MAX_BITMAP_BYTES = 1_500_000

    /** The note's first page as a bitmap that is safe to put into a widget, or null (locked, missing, render failed). */
    suspend fun page(notes: NoteRepository, id: NoteId, dark: Boolean = false): Bitmap? {
        val png = runCatching { notes.renderPreview(id, RENDER_WIDTH_PX, darkTheme = dark) }.getOrNull() ?: return null
        val decoded = runCatching { BitmapFactory.decodeByteArray(png, 0, png.size) }.getOrNull() ?: return null
        return fit(decoded)
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
        return kotlin.math.sqrt(MAX_BITMAP_BYTES.toDouble() / bytes).toFloat()
    }
}

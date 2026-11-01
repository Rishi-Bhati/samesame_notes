package org.samesame.notes.core.export.render

import android.graphics.Bitmap
import android.graphics.Canvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.samesame.notes.core.export.ExportSource
import java.io.ByteArrayOutputStream

/**
 * Rasterises every page of a note to a PNG, keyed by page id. Used by the `.sdocx` exporter as the
 * lossy stroke fallback of spec §13.3 item 4: until the stroke payload layout is decoded, handwriting
 * (and other page-level content) is embedded as a full-page image.
 *
 * [includeText] is false by default because the note's title/body are also written to the `.sdocx`
 * text boxes; rendering them onto the page image as well would duplicate them on re-import. Floating
 * text boxes and images stay part of the image.
 */
suspend fun renderPageImages(
    source: ExportSource,
    scale: Float = 1f,
    includeText: Boolean = false,
): Map<String, ByteArray> = withContext(Dispatchers.Default) {
    val loaded = loadNote(source)
    if (loaded.doc.pages.isEmpty()) return@withContext emptyMap()
    val renderer = NoteRenderer(loaded)
    val s = scale.coerceIn(0.1f, 3f)
    val layers = Layers(text = includeText)
    val out = LinkedHashMap<String, ByteArray>()
    for (i in loaded.doc.pages.indices) {
        val width = (renderer.pageW * s).toInt().coerceAtLeast(1)
        val height = (renderer.pageH(i) * s).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(s, s)
            renderer.drawPage(canvas, i, layers)
            out[loaded.doc.pages[i].id] = ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
    out
}

package org.samesame.notes.core.export.render

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.samesame.notes.core.export.ExportSource
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.bitmapMedia
import org.samesame.notes.core.model.document.TemplateRef
import java.io.ByteArrayOutputStream

/**
 * Renders only the first page of a note to a PNG for the home card previews. Loads the document and
 * the first page, plus the media that page can draw: inline images, the page's image template and the
 * images of its floating objects. Returns null when the note has no pages or rendering fails.
 *
 * [darkTheme] requests the composer's dark-page colour adjustment; it only applies when the note opted
 * in via `style.adjustForDarkMode` (callers also check the app-wide setting).
 */
suspend fun renderFirstPagePreview(source: ExportSource, targetWidthPx: Int, darkTheme: Boolean = false): ByteArray? =
    withContext(Dispatchers.Default) {
        try {
            val doc = source.document()
            val first = doc.pages.firstOrNull() ?: return@withContext null
            val content = source.page(first.id)
            val paths = LinkedHashSet<String>()
            doc.inlineObjects.filterIsInstance<ImageObject>().forEach { paths += it.media }
            (first.template as? TemplateRef.Image)?.let { paths += it.media }
            for (e in content.elements) {
                if (e is ObjectElement) parseFloating(e)?.obj?.bitmapMedia?.let { paths += it }
            }
            val media = LinkedHashMap<String, ByteArray>()
            for (p in paths) source.media(p)?.let { media[p] = it }
            val renderer = NoteRenderer(LoadedNote(doc, mapOf(first.id to content), media), darkTheme && doc.style.adjustForDarkMode)
            val scale = (targetWidthPx.toFloat() / renderer.pageW).coerceIn(0.1f, 1f)
            val bitmap: Bitmap = renderer.renderBitmap(0, scale, Layers())
            try {
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            } finally {
                bitmap.recycle()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null // a corrupt or hostile note must never take the notes list down with it
        } catch (e: StackOverflowError) {
            null
        }
    }

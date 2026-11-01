package org.samesame.notes.feature.composer.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Decoded `ImageObject` bitmaps keyed by their `media/<name>` path. Loading is asynchronous (bytes
 * through a suspend loader, decoding off the main thread); [image] returns null until a decode lands
 * and observes [version], so the canvas re-draws when it does. Reads are safe from the draw phase;
 * writes happen only from the loading coroutines.
 */
class ObjectImageCache(
    private val scope: CoroutineScope,
    private val load: suspend (path: String) -> ByteArray?,
    /** Least recently read bitmaps beyond this are dropped (page backgrounds of long PDFs are large); null = keep all. */
    private val maxEntries: Int? = null,
    private val decode: suspend (bytes: ByteArray) -> ImageBitmap?,
) {
    private val images = LinkedHashMap<String, ImageBitmap?>(16, 0.75f, true)
    private val pending = HashSet<String>()

    private var revision by mutableIntStateOf(0)

    /** Bumped whenever a decode completes; read it in a cache key so layers re-record. */
    val version: Int get() = revision

    fun image(path: String): ImageBitmap? {
        revision // observe for redraw when a decode lands
        images[path]?.let { return it }
        if (images.containsKey(path)) return null // decoded but unreadable / missing
        if (pending.add(path)) {
            scope.launch {
                val bitmap = runCatching {
                    val bytes = load(path) ?: return@runCatching null
                    decode(bytes)
                }.getOrNull()
                images[path] = bitmap
                if (maxEntries != null) while (images.size > maxEntries) images.remove(images.keys.first())
                pending.remove(path)
                revision++
            }
        }
        return null
    }
}

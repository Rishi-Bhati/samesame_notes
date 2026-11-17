package org.samesame.notes.feature.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.samesame.notes.core.data.repository.ImportedImage
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.io.LimitedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Reads received pictures and audio for "New note": upright pixels, bounded sizes, never throws for a bad file (returns null). */
object ReceivedMedia {
    private const val MAX_SIDE = 4096

    /** [bytes] as an upright [ImportedImage], or null when too large or not a picture. EXIF-rotated and heic pictures are re-encoded. */
    fun image(context: Context, uri: Uri, name: String, mime: String?, libraryFileId: String?): ImportedImage? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            LimitedInputStream(it, Limits.MAX_IMAGE_IMPORT_BYTES, "Picture").readBytes()
        } ?: return null
        val ext = ReceiveRouting.imageExtension(name, mime).ifEmpty { "jpg" }
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = matrixFor(orientation)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val heif = ext == "heic" || ext == "heif"
        if (matrix == null && !heif) return ImportedImage(bytes, ext, bounds.outWidth, bounds.outHeight, libraryFileId)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val upright = if (matrix == null) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val png = ext == "png" || ext == "webp" || ext == "gif"
        val out = ByteArrayOutputStream()
        upright.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, out)
        ImportedImage(out.toByteArray(), if (png) "png" else "jpg", upright.width, upright.height, libraryFileId)
    }.getOrNull()

    private fun matrixFor(orientation: Int): Matrix? {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return null
        }
        return m
    }

    /** Copies the audio to a scratch file under the voice size cap; null when over it or unreadable. The caller deletes it. */
    fun audioToScratch(context: Context, uri: Uri, ext: String): File? {
        val dir = File(context.cacheDir, "import").apply { mkdirs() }
        val file = File.createTempFile("audio", ".$ext", dir)
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                LimitedInputStream(input, Limits.VOICE_MAX_FILE_BYTES, "Audio").use { l -> file.outputStream().use { l.copyTo(it) } }
                true
            } ?: false
        }.getOrDefault(false)
        if (!ok) { file.delete(); return null }
        return file
    }

    /** Duration in ms, 0 when the platform cannot tell (the note still gets the recording). */
    fun audioDurationMs(file: File): Long = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally { r.release() }
    }.getOrDefault(0L)
}

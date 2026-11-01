package org.samesame.notes.feature.composer.menus

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.samesame.notes.core.ink.media.BoundedBitmaps
import org.samesame.notes.feature.composer.session.CropFraction
import java.io.ByteArrayOutputStream

/** File side of the object menu (Crop, Save image): bitmap work and the gallery, off the main thread. */
internal object ObjectFileActions {

    fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "m4a", "mp4" -> "audio/mp4"
        "3gp", "3gpp" -> "audio/3gpp"
        "amr" -> "audio/amr"
        else -> "application/octet-stream"
    }

    /** File extension for a decoded picture's MIME type (unknown types are stored as JPEG, the editors' usual output). */
    fun extensionFor(mime: String?): String = when (mime?.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }

    /** The cropped pixels of [bytes] and the extension they are stored with (JPEG stays JPEG, everything else PNG). */
    fun crop(bytes: ByteArray, extension: String, crop: CropFraction): Pair<ByteArray, String>? {
        val src = BoundedBitmaps.decode(bytes) ?: return null
        val (x, y, w, h) = crop.pixels(src.width, src.height).let { listOf(it[0], it[1], it[2], it[3]) }
        val out = try { Bitmap.createBitmap(src, x, y, w, h) } catch (_: OutOfMemoryError) { return null }
        val jpeg = extension.lowercase().let { it == "jpg" || it == "jpeg" }
        val buf = ByteArrayOutputStream()
        out.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (jpeg) 95 else 100, buf)
        if (out !== src) out.recycle()
        src.recycle()
        return buf.toByteArray() to if (jpeg) "jpg" else "png"
    }

    /**
     * Lasso crop: the pixels of [bytes] inside [crop] with everything outside the outline [outline] (fractions of the
     * crop box) transparent, anti-aliased edge. Always PNG (it has transparency).
     */
    fun lassoCrop(bytes: ByteArray, crop: CropFraction, outline: List<org.samesame.notes.core.model.document.Pt>): ByteArray? {
        if (outline.size < 3) return null
        val src = BoundedBitmaps.decode(bytes) ?: return null
        val (x, y, w, h) = crop.pixels(src.width, src.height).let { listOf(it[0], it[1], it[2], it[3]) }
        val out = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) { src.recycle(); return null }
        val path = android.graphics.Path().apply {
            outline.forEachIndexed { i, p -> if (i == 0) moveTo(p.x * w, p.y * h) else lineTo(p.x * w, p.y * h) }
            close()
        }
        val shader = android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        shader.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(-x.toFloat(), -y.toFloat()) })
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        android.graphics.Canvas(out).drawPath(path, paint)
        val buf = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.PNG, 100, buf)
        out.recycle()
        src.recycle()
        return buf.toByteArray()
    }

    /** API 29+: writes the image into Pictures/SameSame notes through MediaStore (no permission needed). */
    fun saveToGallery(context: Context, bytes: ByteArray, extension: String, name: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.$extension")
            put(MediaStore.Images.Media.MIME_TYPE, mimeFor(extension))
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SameSame notes")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }
}

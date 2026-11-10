package org.samesame.notes.feature.files.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build

/**
 * Decodes a library image for the viewer without trusting its header: the size is read first and anything above the
 * pixel budget is downsampled. Every failure (not an image, damaged, out of memory) decodes to null.
 */
internal object ImageLoading {
    /** About 48 MB as ARGB: enough to stay sharp when zoomed, well under what a canvas will draw. */
    const val MAX_PIXELS = 12_000_000L
    const val MAX_SIDE = 8192

    fun decode(context: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) decodeModern(context, uri) else decodeLegacy(context, uri)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** `ImageDecoder` applies the EXIF orientation itself and reads heif. An animated file gives its first frame. */
    @androidx.annotation.RequiresApi(28)
    private fun decodeModern(context: Context, uri: Uri): Bitmap? {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val sample = ZoomMath.sampleSize(info.size.width, info.size.height, MAX_PIXELS, MAX_SIDE)
            if (sample == 0) throw IllegalArgumentException("Image too large")
            decoder.setTargetSampleSize(sample)
            // Software: a large hardware bitmap can fail on the GPU's texture-size limit.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun decodeLegacy(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = ZoomMath.sampleSize(bounds.outWidth, bounds.outHeight, MAX_PIXELS, MAX_SIDE)
        if (sample == 0) return null
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val degrees = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrNull() ?: 0f
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }
}

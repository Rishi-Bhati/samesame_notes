package org.samesame.notes.core.ink.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.samesame.notes.core.model.Limits

/**
 * Decodes note media without trusting the image header: a few-KB PNG may declare 20000 x 20000 pixels.
 * Bounds are read first; images above [maxPixels] are downsampled by a power of two, and anything that
 * still fails (including [OutOfMemoryError]) decodes to null.
 */
object BoundedBitmaps {
    fun decode(bytes: ByteArray, maxPixels: Int = Limits.MAX_DECODE_PIXELS): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxPixels)
        if (sample == 0) return null
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /**
     * The power-of-two `inSampleSize` that brings a [width] x [height] image within [maxPixels], or 0 when
     * the bounds are invalid (not an image) or no reasonable sample size fits.
     */
    fun sampleSize(width: Int, height: Int, maxPixels: Int): Int {
        if (width <= 0 || height <= 0 || maxPixels <= 0) return 0
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels) {
            sample *= 2
            if (sample > MAX_SAMPLE) return 0
        }
        return sample
    }

    private const val MAX_SAMPLE = 1 shl 12
}

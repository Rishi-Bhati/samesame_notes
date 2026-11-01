package org.samesame.notes.core.ink.brush

import android.graphics.Bitmap
import androidx.ink.brush.TextureBitmapStore
import kotlin.random.Random

/**
 * Supplies the runtime-generated textures brush paints refer to by id. Nothing is shipped as an
 * asset: the pencil grain is seeded noise. Pass an instance to `CanvasStrokeRenderer.create`.
 */
object GrainTextureStore : TextureBitmapStore {
    const val GRAIN_ID = "samesame/grain"
    const val GRAIN_SIZE_PX = 128

    private val grain: Bitmap by lazy { generateGrain() }

    override fun get(clientTextureId: String): Bitmap? = if (clientTextureId == GRAIN_ID) grain else null

    /** White pixels whose alpha is speckled; modulating the pen colour by it leaves a paper-tooth look. */
    private fun generateGrain(): Bitmap {
        val rnd = Random(0x5A3E5A3E)
        val px = IntArray(GRAIN_SIZE_PX * GRAIN_SIZE_PX) {
            val v = rnd.nextFloat()
            // Most grains are mostly opaque, a tail of pits is nearly transparent.
            val alpha = if (v < 0.22f) 40 + (v / 0.22f * 80).toInt() else 150 + ((v - 0.22f) / 0.78f * 105).toInt()
            (alpha shl 24) or 0x00FFFFFF
        }
        return Bitmap.createBitmap(px, GRAIN_SIZE_PX, GRAIN_SIZE_PX, Bitmap.Config.ARGB_8888)
    }
}

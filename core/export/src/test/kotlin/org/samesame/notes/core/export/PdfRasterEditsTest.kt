package org.samesame.notes.core.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.samesame.notes.core.export.render.PdfExport
import org.samesame.notes.core.model.document.PdfMarkupKind
import org.samesame.notes.core.model.document.PdfTextMarkup
import org.samesame.notes.core.model.document.QuadPu
import org.samesame.notes.core.model.document.RectPu

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfRasterEditsTest {
    @Test fun `all markup kinds draw at the rotated position`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            for (kind in PdfMarkupKind.entries) {
                val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                PdfExport.rotatePage(canvas, rotation, 100f, 100f)
                PdfExport.drawMarkup(canvas, listOf(PdfTextMarkup(
                    "m", kind, Color.RED.toLong(), listOf(QuadPu.fromRect(RectPu(10f, 10f, 30f, 30f))),
                )))
                val pixels = IntArray(10000)
                bmp.getPixels(pixels, 0, 100, 0, 0, 100, 100)
                val xs = pixels.indices.filter { Color.alpha(pixels[it]) > 0 }.map { it % 100 }
                val ys = pixels.indices.filter { Color.alpha(pixels[it]) > 0 }.map { it / 100 }
                assertThat(xs).isNotEmpty()
                assertThat(xs.average()).isWithin(12.0).of(if (rotation == 90 || rotation == 180) 80.0 else 20.0)
                assertThat(ys.average()).isWithin(12.0).of(if (rotation == 180 || rotation == 270) 80.0 else 20.0)
                bmp.recycle()
            }
        }
    }
}

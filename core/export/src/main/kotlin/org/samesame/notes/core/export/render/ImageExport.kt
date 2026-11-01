package org.samesame.notes.core.export.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.samesame.notes.core.export.ExportOptions
import org.samesame.notes.core.export.ExportTarget
import org.samesame.notes.core.export.ImageBackground
import org.samesame.notes.core.export.ImageFormat
import org.samesame.notes.core.export.ImageLayout
import org.samesame.notes.core.export.ImageOptions
import org.samesame.notes.core.export.sanitizeFileName
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

/** PNG/JPEG export: per page (zipped when several) or one tall image. */
internal object ImageExport {
    private const val MAX_SIDE = 16000
    private const val MAX_PIXELS = 40_000_000L

    fun write(renderer: NoteRenderer, title: String, options: ExportOptions, out: OutputStream): ExportTarget {
        val o = options.image
        val tall = when (o.layout) {
            ImageLayout.SingleTall -> true
            ImageLayout.PerPage -> false
            ImageLayout.Auto -> renderer.layout.infinite
        }
        val layers = when (o.background) {
            ImageBackground.Template -> Layers()
            ImageBackground.White -> Layers(template = false)
            ImageBackground.Transparent -> Layers(background = false, template = false)
        }
        val n = renderer.pageCount
        val single = tall || n == 1
        if (single) {
            val bmp = if (tall && n > 1) tallBitmap(renderer, o, layers) else pageBitmap(renderer, 0, o, layers)
            try { encode(bmp, o, out) } finally { bmp.recycle() }
            return ExportTarget(o.format.extension, o.format.mimeType)
        }
        val zip = ZipOutputStream(out)
        val base = sanitizeFileName(title)
        for (i in 0 until n) {
            val bmp = pageBitmap(renderer, i, o, layers)
            zip.putNextEntry(ZipEntry("%s-%03d.%s".format(base, i + 1, o.format.extension)))
            try { encode(bmp, o, zip) } finally { bmp.recycle() }
            zip.closeEntry()
        }
        zip.finish()
        out.flush()
        return ExportTarget("zip", "application/zip")
    }

    private fun scaleFor(renderer: NoteRenderer, o: ImageOptions, hPu: Float): Float {
        var s = o.scale.coerceIn(0.1f, 8f)
        s = min(s, MAX_SIDE / hPu)
        s = min(s, MAX_SIDE / renderer.pageW)
        val area = (renderer.pageW * s).toDouble() * (hPu * s)
        if (area > MAX_PIXELS) s *= Math.sqrt(MAX_PIXELS / area).toFloat()
        return s
    }

    private fun fill(c: Canvas, o: ImageOptions, layers: Layers) {
        // JPEG has no alpha: when the page background is off, paint white.
        if (o.format == ImageFormat.JPEG && !layers.background) c.drawColor(Color.WHITE)
    }

    private fun pageBitmap(r: NoteRenderer, i: Int, o: ImageOptions, layers: Layers): Bitmap {
        val s = scaleFor(r, o, r.pageH(i))
        val bmp = Bitmap.createBitmap((r.pageW * s).toInt(), (r.pageH(i) * s).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        fill(c, o, layers)
        c.scale(s, s)
        r.drawPage(c, i, layers)
        return bmp
    }

    private fun tallBitmap(r: NoteRenderer, o: ImageOptions, layers: Layers): Bitmap {
        val n = r.pageCount
        val total = (0 until n).sumOf { r.pageH(it).toDouble() }.toFloat()
        val s = scaleFor(r, o, total)
        val bmp = Bitmap.createBitmap((r.pageW * s).toInt(), (total * s).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        fill(c, o, layers)
        c.scale(s, s)
        var top = 0f
        for (i in 0 until n) {
            c.save()
            c.translate(0f, top)
            c.clipRect(0f, 0f, r.pageW, r.pageH(i))
            r.drawPage(c, i, layers)
            c.restore()
            top += r.pageH(i)
        }
        return bmp
    }

    private fun encode(bmp: Bitmap, o: ImageOptions, out: OutputStream) {
        when (o.format) {
            ImageFormat.PNG -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            ImageFormat.JPEG -> bmp.compress(Bitmap.CompressFormat.JPEG, o.jpegQuality.coerceIn(1, 100), out)
        }
    }
}

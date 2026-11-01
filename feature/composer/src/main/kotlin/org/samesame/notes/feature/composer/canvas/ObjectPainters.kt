package org.samesame.notes.feature.composer.canvas

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.samesame.notes.core.model.document.FloatingObject
import org.samesame.notes.core.model.document.TextBoxBorder
import org.samesame.notes.core.model.document.TextBoxObject
import org.samesame.notes.core.model.document.VoiceObject
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.richtext.BodyText
import org.samesame.notes.core.richtext.BodyTextEngine
import org.samesame.notes.core.richtext.RichTextColors
import org.samesame.notes.core.richtext.TextDecorations
import org.samesame.notes.core.richtext.spans.TextRenderEnv

/** Moved to core:richtext so the export renderer lays out text boxes exactly like the editor. */
typealias TextBoxGeometry = org.samesame.notes.core.richtext.layout.TextBoxGeometry

/** Text engine of one text box (static drawing and the editing overlay share it). */
class TextBoxEngine(initial: RichText, geometry: TextBoxGeometry) {
    val body = BodyText(initial, TextRenderEnv())
    val engine = BodyTextEngine(body, geometry)
}

/**
 * Draws text boxes and voice objects into page-space canvases. Text engines are cached per object id and
 * rebuilt when the text changes; main thread (draw phase) only.
 */
class ObjectPainters {
    private class Entry(val engine: TextBoxEngine, var text: RichText, var dark: Boolean)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    private fun engineFor(tb: TextBoxObject, w: Float, h: Float, dark: Boolean): TextBoxEngine {
        val geo = TextBoxGeometry(w, TextBoxGeometry.UNBOUNDED_HEIGHT)
        val e = entries.getOrPut(tb.id) { Entry(TextBoxEngine(tb.text, geo), tb.text, !dark) }
        if (e.text != tb.text) { e.engine.body.reset(tb.text); e.text = tb.text }
        if (e.engine.engine.geometry != geo) e.engine.engine.geometry = geo
        if (e.dark != dark) {
            e.engine.engine.colors = if (dark) RichTextColors.Dark else RichTextColors.Light
            e.engine.body.env.colorTransform = if (dark) { c -> org.samesame.notes.core.ink.DarkModeInk.invert(c) } else { c -> c }
            e.dark = dark
        }
        while (entries.size > 48) entries.remove(entries.keys.first())
        return e.engine
    }

    fun drawTextBox(canvas: Canvas, f: FloatingObject, tb: TextBoxObject, dark: Boolean, rect: RectPu2 = RectPu2(f.rect.left, f.rect.top, f.rect.width, f.rect.height)) {
        val save = canvas.save()
        canvas.translate(rect.x, rect.y)
        if (tb.border == TextBoxBorder.Solid) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 3f; color = if (dark) 0xFFBBBBBB.toInt() else 0xFF555555.toInt()
            }
            canvas.drawRect(0f, 0f, rect.w, rect.h, p)
        }
        canvas.clipRect(0f, 0f, rect.w, rect.h)
        engineFor(tb, rect.w, rect.h, dark).engine.drawPage(canvas, 0, TextDecorations(selectionVisible = false), inPageSpace = true)
        canvas.restoreToCount(save)
    }

    /** The engine to edit [tb] in place (the editing overlay owns its own, seeded from the stored text). */
    fun newEditEngine(tb: TextBoxObject, w: Float, h: Float, dark: Boolean): TextBoxEngine =
        TextBoxEngine(tb.text, TextBoxGeometry(w, TextBoxGeometry.UNBOUNDED_HEIGHT)).also {
            it.engine.colors = if (dark) RichTextColors.Dark else RichTextColors.Light
            it.body.env.colorTransform = if (dark) { c -> org.samesame.notes.core.ink.DarkModeInk.invert(c) } else { c -> c }
        }

    fun drawVoice(canvas: Canvas, rect: RectPu2, v: VoiceObject, dark: Boolean) {
        val save = canvas.save()
        canvas.translate(rect.x, rect.y)
        val w = rect.w; val h = rect.h
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF3A3A3C.toInt() else 0xFFEDEDED.toInt() }
        canvas.drawRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, fill)
        val ink = if (dark) 0xFFE6E6E6.toInt() else 0xFF303030.toInt()
        val tri = Path().apply {
            val cx = h / 2f; val r = h * 0.2f
            moveTo(cx - r * 0.7f, h / 2f - r); lineTo(cx + r, h / 2f); lineTo(cx - r * 0.7f, h / 2f + r); close()
        }
        canvas.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = h * 0.36f }
        canvas.drawText(formatDuration(v.durationMs), h, h / 2f + text.textSize / 3f, text)
        canvas.restoreToCount(save)
    }

    companion object {
        /** "m:ss" (or "h:mm:ss"). */
        fun formatDuration(ms: Long): String {
            val s = (ms.coerceAtLeast(0) / 1000)
            val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
    }
}

/** x, y, width, height of an object in page space. */
data class RectPu2(val x: Float, val y: Float, val w: Float, val h: Float)

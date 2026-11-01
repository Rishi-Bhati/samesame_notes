package org.samesame.notes.core.sdocx

import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextAlign
import org.samesame.notes.core.sdocx.io.LeWriter

/**
 * Builds text-box object records (`base → shapeBase → shape → textBox`) and their text-common
 * blocks from a SameSame [RichText] (objects spec §2, §3, §6). This is the inverse of
 * [org.samesame.notes.core.sdocx.map.SdocxMapper.bodyRichText].
 *
 * Only the span/paragraph kinds the mapper understands are emitted; unknown SameSame styling is
 * dropped (documented loss in spec §14.3). A read-only baseline fontSize + fgColor span covers the
 * whole text, then per-run overrides are emitted so the mapper's later-span-wins order applies.
 */
internal object SdocxTextCodec {

    private const val SPAN_FG_COLOR = 1
    private const val SPAN_FONT_SIZE = 3
    private const val SPAN_BOLD = 5
    private const val SPAN_ITALIC = 6
    private const val SPAN_UNDERLINE = 7
    private const val SPAN_BG_COLOR = 17
    private const val SPAN_STRIKETHROUGH = 20

    private const val PARA_INDENT = 2
    private const val PARA_ALIGN = 3
    private const val PARA_LIST = 5

    const val DEFAULT_COLOR = 0xFF252525L
    const val DEFAULT_FONT_SIZE = 17f

    /** Full text-box object record for a title/body text box. */
    fun textBox(uuid: String, modifiedUs: Long, text: RichText, titled: Boolean): ByteArray {
        val base = baseBlock(uuid, modifiedUs)
        val shapeBase = shapeBaseBlock()
        val shape = shapeBlock(text, titled)
        val textBox = textBoxBlock()
        return base + shapeBase + shape + textBox
    }

    private fun baseBlock(uuid: String, modifiedUs: Long): ByteArray = LeWriter().apply {
        i32(105)
        i16(0)
        i32(0)
        sizedInt(2, 0x01BF)
        sizedInt(4, 0)
        i32(SdocxFormat.WRITE_FORMAT_VERSION)
        uuid36(uuid)
        i64(modifiedUs)
        f64(0.0); f64(0.0); f64(2000.0); f64(2828.0)
        i32(0)
        u8(0)
    }.toByteArray()

    private fun shapeBaseBlock(): ByteArray = LeWriter().apply {
        i32(27)
        i16(6)
        i32(0)
        sizedInt(1, 0)
        sizedInt(1, 0)
        i32(0)
        i32(0)
        i32(0)
        u8(0)
    }.toByteArray()

    private fun shapeBlock(text: RichText, titled: Boolean): ByteArray {
        val tc = textCommon(text)
        val mask = if (titled) 0x1001L else 0x3001L
        val w = LeWriter().apply {
            i32(0) // blockSize placeholder
            i16(7)
            i32(0) // optOffset placeholder
            sizedInt(1, 0x04)
            sizedInt(4, mask)
            i32(4) // shapeType: text box
            f64(0.0); f64(0.0); f64(2000.0); f64(2828.0)
            f32(0f)
            i32(0)
            u8(0)
        }
        val optOffset = w.size
        w.i32(tc.size)
        w.bytes(tc)
        w.u8(1)
        if (!titled) w.u8(3)
        val blockSize = w.size
        w.patchI32(0, blockSize)
        w.patchI32(6, optOffset)
        return w.toByteArray()
    }

    private fun textBoxBlock(): ByteArray = LeWriter().apply {
        i32(15)
        i16(2)
        i32(0)
        sizedInt(1, 0)
        sizedInt(2, 0)
    }.toByteArray()

    /** Serializes the text-common block (objects spec §6). */
    fun textCommon(text: RichText): ByteArray {
        val units = text.text
        val w = LeWriter()
        w.i32(units.length)
        for (c in units) {
            w.u8(c.code and 0xFF)
            w.u8((c.code ushr 8) and 0xFF)
        }

        val spans = buildSpans(text)
        w.i32(spans.size)
        for (s in spans) {
            w.u16(s.size)
            w.bytes(s)
        }

        val paragraphs = buildParagraphs(text)
        w.i32(paragraphs.size)
        for (p in paragraphs) {
            w.u16(p.size)
            w.bytes(p)
        }

        w.f32(16f); w.f32(10f); w.f32(16f); w.f32(10f)
        w.u8(0) // gravity top
        w.u16(1) // one section
        w.i32(0); w.i32(units.length)
        w.i32(0) // tailFlags: no object spans
        w.i32(0) // reserved
        return w.toByteArray()
    }

    private fun buildSpans(text: RichText): List<ByteArray> {
        val n = text.length
        if (n == 0) return emptyList()
        val out = ArrayList<ByteArray>()
        // Baseline: default size + colour over the whole text.
        out += span(SPAN_FONT_SIZE, 0, n - 1, LeWriter().apply { f32(DEFAULT_FONT_SIZE); i32(0) }.toByteArray())
        out += span(SPAN_FG_COLOR, 0, n - 1, LeWriter().apply { i32(DEFAULT_COLOR.toInt()); i32(0) }.toByteArray())

        var offset = 0
        for (run in text.runs) {
            val start = offset
            val end = (offset + run.length - 1).coerceAtLeast(start)
            offset += run.length
            val s = run.style
            val fontSize = s.fontSize
            if (fontSize != null && fontSize != DEFAULT_FONT_SIZE) {
                out += span(SPAN_FONT_SIZE, start, end, LeWriter().apply { f32(fontSize); i32(0) }.toByteArray())
            }
            s.color?.let { c ->
                out += span(SPAN_FG_COLOR, start, end, LeWriter().apply { i32(c.toInt()); i32(0) }.toByteArray())
            }
            s.highlight?.let { c ->
                out += span(SPAN_BG_COLOR, start, end, LeWriter().apply { i32(c.toInt()); i32(0) }.toByteArray())
            }
            if (s.bold) out += span(SPAN_BOLD, start, end, LeWriter().apply { u8(1); i32(0) }.toByteArray())
            if (s.italic) out += span(SPAN_ITALIC, start, end, LeWriter().apply { u8(1); i32(0) }.toByteArray())
            if (s.underline) out += span(SPAN_UNDERLINE, start, end, LeWriter().apply { u8(1); i32(0) }.toByteArray())
            if (s.strikethrough) out += span(SPAN_STRIKETHROUGH, start, end, LeWriter().apply { u8(1); i32(0) }.toByteArray())
        }
        return out
    }

    private fun buildParagraphs(text: RichText): List<ByteArray> {
        if (text.length == 0) return emptyList()
        val out = ArrayList<ByteArray>()
        val paragraphs = text.paragraphs
        var start = 0
        for (i in paragraphs.indices) {
            val para = paragraphs[i]
            val end = if (i == paragraphs.size - 1) text.length - 1 else start + paragraphLength(text, start)
            emitParagraph(out, para, start, end.coerceAtLeast(start))
            start = (end + 1).coerceAtMost(text.length)
        }
        return out
    }

    private fun paragraphLength(text: RichText, start: Int): Int {
        val nl = text.text.indexOf('\n', start)
        return if (nl < 0) text.length - 1 - start else nl - start
    }

    private fun emitParagraph(out: MutableList<ByteArray>, para: ParagraphStyle, start: Int, end: Int) {
        if (para.align != TextAlign.Left) {
            val align = when (para.align) {
                TextAlign.Right -> 1
                TextAlign.Center -> 2
                TextAlign.Left -> 0
            }
            out += paragraph(PARA_ALIGN, start, end, LeWriter().apply { i32(align); i32(0) }.toByteArray())
        }
        if (para.indent > 0) {
            out += paragraph(PARA_INDENT, start, end, LeWriter().apply { i32(para.indent); i32(0) }.toByteArray())
        }
        when (para.list) {
            ListKind.None -> Unit
            ListKind.Bullet -> out += paragraph(PARA_LIST, start, end, LeWriter().apply { i32(1); i32(0) }.toByteArray())
            ListKind.Numbered -> out += paragraph(PARA_LIST, start, end, LeWriter().apply { i32(4); i32(0) }.toByteArray())
            ListKind.Checklist -> out += paragraph(
                PARA_LIST, start, end,
                LeWriter().apply { i32(2); i32(if (para.checked) 1 else 0) }.toByteArray(),
            )
        }
    }

    private fun span(kind: Int, start: Int, end: Int, payload: ByteArray): ByteArray =
        LeWriter().apply { i32(kind); i32(start); i32(end); i32(1); bytes(payload) }.toByteArray()

    private fun paragraph(kind: Int, start: Int, end: Int, payload: ByteArray): ByteArray =
        LeWriter().apply { i32(kind); i32(start); i32(end); bytes(payload) }.toByteArray()
}

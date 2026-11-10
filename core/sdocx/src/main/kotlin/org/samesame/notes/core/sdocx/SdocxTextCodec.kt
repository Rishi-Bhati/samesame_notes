package org.samesame.notes.core.sdocx

import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextAlign
import org.samesame.notes.core.sdocx.io.LeWriter
import org.samesame.notes.core.sdocx.record.TextBoxRecord
import org.samesame.notes.core.sdocx.record.TextCommon

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
    private const val PARA_PARSING_STATE = 6

    const val DEFAULT_COLOR = 0xFF252525L
    const val DEFAULT_FONT_SIZE = 17f

    /**
     * Full text-box object record for a title/body text box.
     *
     * A new record reproduces, field for field, the title and body records of files written by the
     * reference app (verified against the device fixtures). When [previous] is given (an imported
     * note's text was edited) every block of the source record is reused as it is and only the
     * text-common inside its shape block is replaced: span/paragraph kinds SameSame cannot model
     * (font name, hyperlink, timestamp, line spacing, …) and inline object anchors are re-anchored
     * onto unchanged text instead of being dropped (spec §12.2). The object keeps its uuid and gets
     * [modifiedUs] as its modified time.
     */
    fun textBox(
        uuid: String,
        modifiedUs: Long,
        text: RichText,
        titled: Boolean,
        previous: TextBoxRecord? = null,
    ): ByteArray {
        if (previous != null) {
            val tc = textCommon(text, previous.text, titled)
            replaceTextCommon(previous.raw, tc, modifiedUs)?.let { return it }
        }
        val base = baseBlock(uuid, modifiedUs)
        val shapeBase = shapeBaseBlock()
        val shape = shapeBlock(textCommon(text, previous?.text, titled), titled)
        val textBox = textBoxBlock()
        return base + shapeBase + shape + textBox
    }

    /**
     * [record] with the text-common of its shape block (tag 7, optional bit 0) replaced by [tc] and
     * the base block's modified time set. Null when the record does not have that shape.
     */
    private fun replaceTextCommon(record: ByteArray, tc: ByteArray, modifiedUs: Long): ByteArray? = try {
        val out = LeWriter(record.size + tc.size)
        var pos = 0
        var replaced = false
        var first = true
        while (pos + 10 <= record.size) {
            val r = org.samesame.notes.core.sdocx.io.LeReader(record, pos)
            val blockSize = r.i32()
            if (blockSize < 10 || pos + blockSize > record.size) return null
            val tag = r.i16()
            val optOffset = r.i32()
            val block = record.copyOfRange(pos, pos + blockSize)
            if (first) {
                // Object base: fixed layout with the modified time at +60 when the two flag words
                // have their usual widths (objects spec §2).
                if (tag != 0 || blockSize < 105 || block[10].toInt() != 2 || block[13].toInt() != 4) return null
                val w = LeWriter(block.size)
                w.bytes(block)
                w.patchI64(60, modifiedUs)
                out.bytes(w.toByteArray())
                first = false
            } else if (tag == 7 && !replaced) {
                val m = org.samesame.notes.core.sdocx.io.LeReader(block, 10)
                m.sizedInt()
                val mask = m.sizedInt().value
                if (mask and 1L == 0L || optOffset <= 0 || optOffset + 4 > blockSize) return null
                val oldLen = org.samesame.notes.core.sdocx.io.LeReader(block, optOffset).i32()
                val after = optOffset + 4 + oldLen
                if (oldLen < 0 || after > blockSize) return null
                val w = LeWriter(blockSize + tc.size)
                w.bytes(block.copyOfRange(0, optOffset))
                w.i32(tc.size)
                w.bytes(tc)
                w.bytes(block.copyOfRange(after, blockSize))
                w.patchI32(0, w.size)
                out.bytes(w.toByteArray())
                replaced = true
            } else {
                out.bytes(block)
            }
            pos += blockSize
        }
        if (replaced && pos == record.size) out.toByteArray() else null
    } catch (_: Exception) {
        null
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
        f64(0.0); f64(0.0); f64(0.0); f64(0.0)
        i32(0)
        u8(0)
    }.toByteArray()

    /** Shape base with a black solid line colour and a zero-width line style, as in every real text box. */
    private fun shapeBaseBlock(): ByteArray = LeWriter().apply {
        i32(66)
        i16(6)
        i32(27)
        sizedInt(1, 0)
        sizedInt(1, 0x0C)
        i32(0) // connection points
        i32(4) // byte size of the connected list that follows
        i32(0) // connected objects
        u8(0)
        // bit 2: line colour effect (objects spec §3.5)
        i32(19)
        u8(1); u8(0); u8(2)
        i32(0xFF000000.toInt())
        u8(0); i16(0)
        f32(0f); f32(0f)
        u8(0)
        // bit 3: line style effect (objects spec §3.6)
        i32(12)
        f32(0f)
        bytes(ByteArray(8))
    }.toByteArray()

    private fun shapeBlock(tc: ByteArray, titled: Boolean): ByteArray {
        val mask = if (titled) 0x1001L else 0x3001L
        val w = LeWriter().apply {
            i32(0) // blockSize placeholder
            i16(7)
            i32(0) // optOffset placeholder
            sizedInt(1, 0x04)
            sizedInt(4, mask)
            i32(4) // shapeType: text box
            f64(0.0); f64(0.0); f64(0.0); f64(0.0)
            f32(0f)
            i32(0)
            u8(0)
        }
        val optOffset = w.size
        w.i32(tc.size)
        w.bytes(tc)
        w.u8(2) // ellipsis type, as written by the reference app
        if (!titled) w.u8(2) // auto-fit
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

    /**
     * Serializes the text-common block (objects spec §6) the way the reference app lays it out:
     * the title has no spans and one parsing-state paragraph; the body has font-size and colour
     * spans over all of its text and, per paragraph, an alignment record, optional indent and list
     * records and a parsing-state record. Paragraph records address paragraphs by index
     * (`[i, i+1)`), spans address UTF-16 units with an exclusive end.
     */
    fun textCommon(text: RichText, previous: TextCommon? = null, titled: Boolean = false): ByteArray {
        val units = text.text
        val w = LeWriter()
        w.i32(units.length)
        for (c in units) {
            w.u8(c.code and 0xFF)
            w.u8((c.code ushr 8) and 0xFF)
        }

        val anchor = previous?.let { Anchor.of(it.text, units) }
        val spans = (if (titled) emptyList() else buildSpans(text)) + preservedSpans(previous, anchor)
        w.i32(spans.size)
        for (s in spans) {
            w.u16(s.size)
            w.bytes(s)
        }

        val paragraphs = (if (titled) listOf(paragraph(PARA_PARSING_STATE, 0, 1, 0, 0)) else buildParagraphs(text)) +
            preservedParagraphs(previous, anchor, units)
        w.i32(paragraphs.size)
        for (p in paragraphs) {
            w.u16(p.size)
            w.bytes(p)
        }

        if (previous != null) {
            w.f32(previous.marginLeft); w.f32(previous.marginTop)
            w.f32(previous.marginRight); w.f32(previous.marginBottom)
            w.u8(previous.gravity)
        } else if (titled) {
            w.f32(11.25f); w.f32(0f); w.f32(11.25f); w.f32(0f)
            w.u8(0)
        } else {
            w.f32(16f); w.f32(10f); w.f32(16f); w.f32(10f)
            w.u8(0) // gravity top
        }
        // Sections: none for a title; a body has two, (-1, 1) (0, 0) when empty and
        // (0, length) (length, 0) otherwise. A source with another count keeps its own while valid.
        val sourceSections = previous?.sections
        val twoSections = if (sourceSections != null) sourceSections.size == 2 else !titled
        when {
            twoSections && units.isEmpty() -> { w.u16(2); w.i32(-1); w.i32(1); w.i32(0); w.i32(0) }
            twoSections -> { w.u16(2); w.i32(0); w.i32(units.length); w.i32(units.length); w.i32(0) }
            sourceSections != null && sourceSections.all { it.start in 0..units.length && it.length >= 0 && it.start + it.length <= units.length } -> {
                w.u16(sourceSections.size)
                for (s in sourceSections) { w.i32(s.start); w.i32(s.length) }
            }
            else -> w.u16(0)
        }

        val tailFlags = previous?.tailFlags ?: 0
        val objectSpans = preservedObjectSpans(previous, anchor, units)
        w.i32(tailFlags)
        w.i32(previous?.reservedTail ?: 0)
        if (tailFlags and TextCommon.TAIL_FLAG_OBJECT_SPANS != 0) {
            w.i32(objectSpans.size)
            for (span in objectSpans) {
                val entryLen = 4 + 4 + span.record.size + 4
                w.i32(entryLen)
                w.i32(span.record.size)
                w.i32(span.objectType)
                w.bytes(span.record)
                w.i32(span.charPos)
            }
        }
        previous?.tailExtra?.let { w.bytes(it) }
        return w.toByteArray()
    }

    /**
     * Maps old text offsets to the edited text. Offsets inside the common prefix or suffix are
     * unchanged; offsets inside the edited middle are dropped (the style of the edited region comes
     * from SameSame's model). This keeps unknown formatting on untouched text across an edit.
     */
    private class Anchor(val oldLength: Int, val prefix: Int, val suffix: Int, val newLength: Int) {
        private val delta = newLength - oldLength

        fun map(offset: Int): Int? = when {
            offset <= prefix -> offset
            offset >= oldLength - suffix -> offset + delta
            else -> null
        }

        fun mapRange(start: Int, endExclusive: Int): Pair<Int, Int>? {
            val s = map(start) ?: return null
            val e = map(endExclusive) ?: return null
            return if (e > s) s to e else null
        }

        companion object {
            fun of(old: String, new: String): Anchor {
                val max = minOf(old.length, new.length)
                var prefix = 0
                while (prefix < max && old[prefix] == new[prefix]) prefix++
                var suffix = 0
                while (suffix < max - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
                return Anchor(old.length, prefix, suffix, new.length)
            }
        }
    }

    /** Span kinds rebuilt from SameSame's `CharStyle`; everything else is carried over raw. */
    private val MODELED_SPAN_KINDS = setOf(SPAN_FG_COLOR, SPAN_FONT_SIZE, SPAN_BOLD, SPAN_ITALIC, SPAN_UNDERLINE, SPAN_BG_COLOR, SPAN_STRIKETHROUGH)

    /** Paragraph kinds rebuilt from SameSame's `ParagraphStyle`. */
    private val MODELED_PARA_KINDS = setOf(PARA_INDENT, PARA_ALIGN, PARA_LIST, PARA_PARSING_STATE)

    private fun preservedSpans(previous: TextCommon?, anchor: Anchor?): List<ByteArray> {
        if (previous == null || anchor == null) return emptyList()
        val out = ArrayList<ByteArray>()
        for (span in previous.spans) {
            if (span.kind in MODELED_SPAN_KINDS || span.raw.size < 16) continue
            val range = anchor.mapRange(span.start, span.end) ?: continue
            out += patchRange(span.raw, range.first, range.second)
        }
        return out
    }

    /**
     * Paragraph records of kinds this module does not model, moved to the paragraph they belonged to.
     * A paragraph keeps them when its text lies wholly in the part of the text the edit left alone.
     */
    private fun preservedParagraphs(previous: TextCommon?, anchor: Anchor?, text: String): List<ByteArray> {
        if (previous == null || anchor == null) return emptyList()
        val oldStarts = paragraphStarts(previous.text)
        val newStarts = paragraphStarts(text)
        fun mapIndex(index: Int): Int? {
            if (index !in oldStarts.indices) return null
            val start = oldStarts[index]
            val end = if (index + 1 < oldStarts.size) oldStarts[index + 1] else previous.text.length
            val s = anchor.map(start) ?: return null
            anchor.map(end) ?: return null
            val mapped = newStarts.indexOf(s)
            return if (mapped >= 0) mapped else null
        }
        val out = ArrayList<ByteArray>()
        for (para in previous.paragraphs) {
            if (para.kind in MODELED_PARA_KINDS || para.raw.size < 12) continue
            val first = mapIndex(para.start) ?: continue
            val last = mapIndex((para.end - 1).coerceAtLeast(para.start)) ?: continue
            if (last < first) continue
            out += patchRange(para.raw, first, last + 1)
        }
        return out
    }

    private fun paragraphStarts(text: String): List<Int> {
        val starts = ArrayList<Int>()
        starts += 0
        for (i in text.indices) if (text[i] == '\n') starts += i + 1
        return starts
    }

    private fun preservedObjectSpans(
        previous: TextCommon?,
        anchor: Anchor?,
        text: String,
    ): List<TextCommon.ObjectSpan> {
        if (previous == null || anchor == null) return emptyList()
        val out = ArrayList<TextCommon.ObjectSpan>()
        for (span in previous.objectSpans) {
            val pos = anchor.map(span.charPos) ?: continue
            if (pos !in 0..text.length) continue
            // Keep an inline object only while its placeholder character is still there.
            if (pos < text.length && text[pos] != org.samesame.notes.core.model.richtext.OBJECT_CHAR) continue
            out += TextCommon.ObjectSpan(span.objectType, span.record, pos)
        }
        return out
    }

    /** Copies a length-prefixed span/paragraph body with new start/end values. */
    private fun patchRange(raw: ByteArray, start: Int, end: Int): ByteArray {
        val out = raw.copyOf()
        putI32(out, 4, start)
        putI32(out, 8, end)
        return out
    }

    private fun putI32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun buildSpans(text: RichText): List<ByteArray> {
        val n = text.length
        val out = ArrayList<ByteArray>()
        if (n == 0) {
            out += span(SPAN_FONT_SIZE, 0, 0, f32Payload(DEFAULT_FONT_SIZE))
            out += span(SPAN_FG_COLOR, 0, 0, colourPayload(DEFAULT_COLOR))
            return out
        }
        val styles = ArrayList<org.samesame.notes.core.model.richtext.CharStyle>(n)
        for (run in text.runs) repeat(run.length) { if (styles.size < n) styles += run.style }
        while (styles.size < n) styles += org.samesame.notes.core.model.richtext.CharStyle()

        /** One span per maximal range over which [value] is the same and non-null. */
        fun <T : Any> ranges(value: (org.samesame.notes.core.model.richtext.CharStyle) -> T?, emit: (Int, Int, T) -> Unit) {
            var start = 0
            while (start < n) {
                val v = value(styles[start])
                var end = start + 1
                while (end < n && value(styles[end]) == v) end++
                if (v != null) emit(start, end, v)
                start = end
            }
        }
        ranges({ it.fontSize ?: DEFAULT_FONT_SIZE }) { s, e, v -> out += span(SPAN_FONT_SIZE, s, e, f32Payload(v)) }
        ranges({ it.color ?: DEFAULT_COLOR }) { s, e, v -> out += span(SPAN_FG_COLOR, s, e, colourPayload(v)) }
        ranges({ it.highlight }) { s, e, v -> out += span(SPAN_BG_COLOR, s, e, colourPayload(v)) }
        ranges({ if (it.bold) true else null }) { s, e, _ -> out += span(SPAN_BOLD, s, e, flagPayload(8)) }
        ranges({ if (it.italic) true else null }) { s, e, _ -> out += span(SPAN_ITALIC, s, e, flagPayload(8)) }
        // Underline: on, style 0 (single), pad, colour 0 (objects spec §6.1).
        ranges({ if (it.underline) true else null }) { s, e, _ -> out += span(SPAN_UNDERLINE, s, e, flagPayload(8)) }
        ranges({ if (it.strikethrough) true else null }) { s, e, _ -> out += span(SPAN_STRIKETHROUGH, s, e, flagPayload(4)) }
        return out
    }

    private fun f32Payload(value: Float): ByteArray = LeWriter().apply { f32(value); i32(0) }.toByteArray()

    private fun colourPayload(argb: Long): ByteArray = LeWriter().apply { i32(argb.toInt()); i32(0) }.toByteArray()

    /** `u8 1` then zero padding to [size] bytes. */
    private fun flagPayload(size: Int): ByteArray = ByteArray(size).also { it[0] = 1 }

    private fun buildParagraphs(text: RichText): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        if (text.length == 0) {
            out += paragraph(PARA_ALIGN, 0, 1, 0, 0)
            return out
        }
        val count = text.text.count { it == '\n' } + 1
        for (i in 0 until count) {
            val para = text.paragraphs.getOrNull(i) ?: ParagraphStyle()
            val align = when (para.align) {
                TextAlign.Right -> 1
                TextAlign.Center -> 2
                TextAlign.Left -> 0
            }
            out += paragraph(PARA_ALIGN, i, i + 1, align, 0)
            if (para.indent > 0) out += paragraph(PARA_INDENT, i, i + 1, para.indent, 0)
            when (para.list) {
                ListKind.None -> Unit
                // 8 = solid circle, the reference app's "Bullet points"; 4 = digits; 2 = checkbox.
                ListKind.Bullet -> out += paragraph(PARA_LIST, i, i + 1, 8, 0)
                ListKind.Numbered -> out += paragraph(PARA_LIST, i, i + 1, 4, 0)
                ListKind.Checklist -> out += paragraph(PARA_LIST, i, i + 1, 2, if (para.checked) 1 else 0)
            }
            out += paragraph(PARA_PARSING_STATE, i, i + 1, 1, 0)
        }
        return out
    }

    private fun span(kind: Int, start: Int, end: Int, payload: ByteArray): ByteArray =
        LeWriter().apply { i32(kind); i32(start); i32(end); i32(1); bytes(payload) }.toByteArray()

    private fun paragraph(kind: Int, start: Int, end: Int, first: Int, second: Int): ByteArray =
        LeWriter().apply { i32(kind); i32(start); i32(end); i32(first); i32(second) }.toByteArray()
}

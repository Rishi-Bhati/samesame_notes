package org.samesame.notes.core.sdocx

import org.samesame.notes.core.sdocx.io.LeReader
import org.samesame.notes.core.sdocx.io.LeWriter

/**
 * The layer optional-field area (core spec §6.2). It is **not** self-delimiting and bit 0's width
 * is ambiguous in the reference writer (i32) versus reader (one byte), so the area is parsed with
 * both widths and the one that lands exactly on the end wins. Every field is kept as its raw bytes;
 * rewriting a timestamp therefore preserves fields this module does not model.
 */
internal object LayerOptionalCodec {

    class Fields(
        private val transparency: ByteArray?,
        private val backgroundColor: ByteArray?,
        private val name: ByteArray?,
        private val id: ByteArray?,
        private val modifiedTime: ByteArray?,
        private val thumbnail: ByteArray?,
    ) {
        val layerId: String? get() = id?.let { readStr16(it) }
        val time: Long? get() = modifiedTime?.let { readI64(it) }

        fun withTime(value: Long): Fields = Fields(
            transparency, backgroundColor, name, id, i64(value), thumbnail,
        )

        fun write(): ByteArray {
            val w = LeWriter()
            transparency?.let { w.bytes(it) }
            backgroundColor?.let { w.bytes(it) }
            name?.let { w.bytes(it) }
            id?.let { w.bytes(it) }
            modifiedTime?.let { w.bytes(it) }
            thumbnail?.let { w.bytes(it) }
            return w.toByteArray()
        }
    }

    fun parse(raw: ByteArray, mask: Int): Fields? {
        if (mask == 0) return if (raw.isEmpty()) Fields(null, null, null, null, null, null) else null
        // Prefer a parse that consumes the whole area exactly; bit 0 can be one byte or four.
        for (transparencyWidth in intArrayOf(1, 4)) {
            val fields = parse(raw, mask, transparencyWidth) ?: continue
            if (fields.first == raw.size) return fields.second
        }
        return parse(raw, mask, 4)?.second
    }

    /** Returns (consumed, fields) or null when the area is truncated. */
    private fun parse(raw: ByteArray, mask: Int, transparencyWidth: Int): Pair<Int, Fields>? {
        val r = LeReader(raw)
        return try {
            val transparency = if (mask and 0x1 != 0) r.bytes(transparencyWidth) else null
            val backgroundColor = if (mask and 0x2 != 0) r.bytes(4) else null
            val name = if (mask and 0x4 != 0) str16Raw(r) else null
            val id = if (mask and 0x8 != 0) str16Raw(r) else null
            val time = if (mask and 0x10 != 0) r.bytes(8) else null
            val thumbnail = if (mask and 0x20 != 0) r.bytes(4) else null
            r.pos to Fields(transparency, backgroundColor, name, id, time, thumbnail)
        } catch (_: Exception) {
            null
        }
    }

    private fun str16Raw(r: LeReader): ByteArray {
        val start = r.pos
        val n = r.i16()
        require(n >= 0) { "bad str16 length $n" }
        r.bytes(n * 2)
        return r.bytesAt(start, r.pos - start)
    }

    private fun readStr16(raw: ByteArray): String? {
        val r = LeReader(raw)
        return try { r.str16() } catch (_: Exception) { null }
    }

    private fun readI64(raw: ByteArray): Long? =
        if (raw.size < 8) null else LeReader(raw).i64()

    private fun i64(value: Long): ByteArray = LeWriter().apply { i64(value) }.toByteArray()
}

/** Copy of [from, to) from this reader's backing array (used to keep a field's raw bytes). */
private fun LeReader.bytesAt(from: Int, length: Int): ByteArray {
    val saved = pos
    seek(from)
    val out = bytes(length)
    seek(saved)
    return out
}

package org.samesame.notes.core.sdocx

import org.samesame.notes.core.sdocx.io.LeWriter
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Minimal ZIP writer for `.sdocx` output. `java.util.zip.ZipOutputStream` writes deflated entries
 * with a trailing data descriptor (general-purpose bit 3) and zero sizes in the local header; files
 * from the reference app never do that. This writer puts CRC and both sizes in every local header,
 * uses the same header values as those files (version needed 2.0, UTF-8 name flag, no extra field)
 * and lets the caller choose stored or deflated per entry.
 */
internal class SdocxZip(private val dosDateTime: Long) {

    private class Entry(val name: ByteArray, val method: Int, val flags: Int, val crc: Long, val packed: Int, val size: Int, val offset: Int)

    private val out = LeWriter(4096)
    private val entries = ArrayList<Entry>()

    fun add(name: String, data: ByteArray, stored: Boolean = false) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(data) }.value
        val packed = if (stored) data else deflate(data)
        val entry = Entry(
            name = nameBytes,
            method = if (stored) 0 else 8,
            // Bit 11: UTF-8 names. Bits 1-2: the "super fast" deflate option, as the level used below.
            flags = if (stored) 0x800 else 0x806,
            crc = crc,
            packed = packed.size,
            size = data.size,
            offset = out.size,
        )
        out.i32(0x04034b50)
        header(entry)
        out.bytes(nameBytes)
        out.bytes(packed)
        entries += entry
    }

    private fun header(e: Entry) {
        out.u16(20) // version needed
        out.u16(e.flags)
        out.u16(e.method)
        out.u16((dosDateTime and 0xFFFF).toInt())
        out.u16(((dosDateTime shr 16) and 0xFFFF).toInt())
        out.i32(e.crc.toInt())
        out.i32(e.packed)
        out.i32(e.size)
        out.u16(e.name.size)
        out.u16(0) // extra length
    }

    fun finish(): ByteArray {
        val start = out.size
        for (e in entries) {
            out.i32(0x02014b50)
            out.u16(0) // version made by
            header(e)
            out.u16(0) // comment length
            out.u16(0) // disk number
            out.u16(0) // internal attributes
            out.i32(0) // external attributes
            out.i32(e.offset)
            out.bytes(e.name)
        }
        val size = out.size - start
        out.i32(0x06054b50)
        out.u16(0); out.u16(0)
        out.u16(entries.size); out.u16(entries.size)
        out.i32(size)
        out.i32(start)
        out.u16(0)
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val buffer = ByteArray(maxOf(64, data.size / 2))
            val packed = java.io.ByteArrayOutputStream(buffer.size)
            while (!deflater.finished()) packed.write(buffer, 0, deflater.deflate(buffer))
            return packed.toByteArray()
        } finally {
            deflater.end()
        }
    }
}

package org.samesame.notes.core.sdocx

import org.samesame.notes.core.sdocx.io.LeReader
import org.samesame.notes.core.sdocx.io.LeWriter
import org.samesame.notes.core.sdocx.record.MediaEntry
import org.samesame.notes.core.sdocx.record.MediaRegistry
import org.samesame.notes.core.sdocx.record.RectF64
import java.security.MessageDigest

/** A picture to embed: its file bytes, extension and the size it is shown at, in `.sdocx` page units. */
class SdocxImageSource(val bytes: ByteArray, val extension: String, val width: Double, val height: Double)

/**
 * Image objects in the one form a real file proves: a picture inside the body text, stored as an
 * object span whose record is `base → shapeBase → shape → image`, with the bitmap referenced by the
 * shape's image fill (objects spec §5.7). [record] reproduces that record byte for byte for the
 * same inputs.
 */
object SdocxImages {

    /** What the editor needs from an image record. */
    class Info(val uuid: String, val rect: RectF64, val imageId: Int)

    /** Layout type of an object that sits in the text flow (base optional bit 15). */
    private const val LAYOUT_INLINE = 3

    fun record(uuid: String, timeUs: Long, rect: RectF64, imageId: Int, noteWidth: Int): ByteArray {
        val cx = (rect.left + rect.right) / 2
        val cy = (rect.top + rect.bottom) / 2
        val base = LeWriter().apply {
            i32(122)
            i16(0)
            i32(105)
            sizedInt(2, 0x01BF)
            sizedInt(4, 0xE000) // append time, owner page size, layout type
            i32(SdocxFormat.WRITE_FORMAT_VERSION)
            uuid36(uuid)
            i64(timeUs)
            rect(this, rect)
            i32(0) // timeStamp
            u8(0) // resizable
            i64(timeUs) // append time
            i32(noteWidth); i32(0) // owner page size: width only for an object in the text
            u8(LAYOUT_INLINE)
        }.toByteArray()

        val shapeBase = LeWriter().apply {
            i32(130)
            i16(6)
            i32(91)
            sizedInt(1, 0)
            sizedInt(1, 0x0C)
            // Four connection points: the middle of the top, right, bottom and left edge.
            i32(4)
            f64(cx); f64(rect.top)
            f64(rect.right); f64(cy)
            f64(cx); f64(rect.bottom)
            f64(rect.left); f64(cy)
            i32(4) // byte size of the connected list
            i32(0)
            u8(0)
            i32(19) // line colour: black, solid
            u8(1); u8(0); u8(2)
            i32(0xFF000000.toInt())
            u8(0); i16(0)
            f32(0f); f32(0f)
            u8(0)
            i32(12) // line style: zero width
            bytes(ByteArray(12))
        }.toByteArray()

        val shape = LeWriter().apply {
            i32(0) // blockSize placeholder
            i16(7)
            i32(0) // optOffset placeholder
            sizedInt(1, 0)
            sizedInt(4, 0x1020) // image fill + ellipsis type
            i32(4) // shapeType
            rect(this, rect)
            f32(0f)
            // Outline: move to the top-left corner, three lines round the rectangle, close.
            i32(73)
            i32(5)
            u8(1); f64(rect.left); f64(rect.top)
            u8(2); f64(rect.right); f64(rect.top)
            u8(2); f64(rect.right); f64(rect.bottom)
            u8(2); f64(rect.left); f64(rect.bottom)
            u8(6)
            u8(0) // control points
            val optOffset = size
            i32(62) // fill effect length (after the kind byte)
            u8(2) // fill kind: image
            u8(0) // image type
            i32(imageId) // media bind number
            bytes(ByteArray(16)) // stretch offset
            bytes(ByteArray(8)) // tiling offset
            f32(100f); f32(100f) // tiling scale
            f32(0f) // transparency
            u8(0) // rotatable
            bytes(ByteArray(16)) // nine-patch rect
            i32(noteWidth)
            u8(2) // ellipsis type
            patchI32(0, size)
            patchI32(6, optOffset)
        }.toByteArray()

        val image = LeWriter().apply {
            i32(17)
            i16(3)
            i32(0)
            sizedInt(1, 0)
            sizedInt(4, 0)
        }.toByteArray()
        return base + shapeBase + shape + image
    }

    private fun rect(w: LeWriter, r: RectF64) {
        w.f64(r.left); w.f64(r.top); w.f64(r.right); w.f64(r.bottom)
    }

    /**
     * Reads the bounds and the bitmap reference of an image record: the shape's image fill, or the
     * base block's attached file id when there is no fill. Null when the record has neither or does
     * not have the usual layout.
     */
    fun info(record: ByteArray): Info? = try {
        val base = parseObjectBase(record) ?: return null
        if (record[10].toInt() != 2 || record[13].toInt() != 4) return null
        val head = LeReader(record)
        val baseSize = head.i32()
        head.i16()
        val baseOpt = head.i32()
        val baseMask = LeReader(record, 14).i32()
        val r = LeReader(record, 68)
        val rect = RectF64(r.f64(), r.f64(), r.f64(), r.f64())
        var imageId: Int? = null
        if (baseMask and 0x40 != 0 && baseOpt > 0) {
            // Optional fields before bit 6 are not self-delimiting; only the simple case is read.
            if (baseMask and 0x3F == 0) imageId = LeReader(record, baseOpt).i32()
        }
        var pos = baseSize
        while (pos + 10 <= record.size) {
            val b = LeReader(record, pos)
            val size = b.i32()
            if (size < 10 || pos + size > record.size) break
            val tag = b.i16()
            val opt = b.i32()
            if (tag == 7 && opt > 0) {
                b.sizedInt()
                val mask = b.sizedInt().value
                val o = LeReader(record, pos + opt)
                if (mask and 0x1L != 0L) o.bytes(o.i32())
                if (mask and 0x2L != 0L) o.u8()
                for (bit in longArrayOf(0x4, 0x8, 0x10)) if (mask and bit != 0L) o.i32()
                if (mask and 0x20L != 0L) {
                    o.i32()
                    if (o.u8() == 2) {
                        o.u8()
                        val id = o.i32()
                        if (id >= 0) imageId = id
                    }
                }
            }
            pos += size
        }
        imageId?.let { Info(base.uuid, rect, it) }
    } catch (_: Exception) {
        null
    }

    /** Adds [bytes] as a new media file named `<bind>@<sha prefix>.<ext>`, as real files name theirs. */
    internal class MediaAdder(private val source: MediaRegistry, files: Map<String, ByteArray>) {
        val entries = ArrayList(source.entries)
        val files = LinkedHashMap(files)
        private var next = (source.entries.maxOfOrNull { it.bindNumber } ?: -1) + 1
        var changed = false
            private set

        fun add(bytes: ByteArray, extension: String, timeUs: Long, label: String = ""): Int {
            val bind = next++
            val hash = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
            val ext = extension.lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
            val name = "$bind@${label.ifEmpty { hash.take(12) }}.$ext"
            entries += MediaEntry(
                bindNumber = bind,
                fileName = name,
                fileHash = hash,
                refCount = 1,
                modifiedTime = timeUs,
                isFileAttached = if (source.extended) true else null,
            )
            files["media/$name"] = bytes
            changed = true
            return bind
        }

        fun registry(): MediaRegistry =
            MediaRegistry(source.extended, source.formatVersion, entries, source.hasTerminator || source.extended)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

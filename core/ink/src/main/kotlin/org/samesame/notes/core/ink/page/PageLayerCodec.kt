package org.samesame.notes.core.ink.page

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import org.samesame.notes.core.ink.PenBrushes
import org.samesame.notes.core.ink.StrokeCodec
import org.samesame.notes.core.model.Limits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ThreadLocalRandom

/**
 * Page layer file, v2 (reads v1 too). See docs/editor-architecture.md 1.5.
 *
 * ```
 * magic "SSNK" · u16 version = 2 · u16 flags(0) · u32 elementCount
 * element: u8 tag · u32 byteLength · payload   (unknown tags are skipped)
 *   tag 1 STROKE: i64 id · utf familyKey · i32 argb · f32 size · f32 epsilon · u32 n · StrokeInputBatch
 *   tag 2 OBJECT: i64 id · 4 x f32 bounds (xMin,yMin,xMax,yMax) · utf8 JSON (rest of payload)
 * ```
 */
object PageLayerCodec {
    private const val MAGIC = 0x53534E4B // "SSNK"
    const val VERSION = 2
    private const val TAG_STROKE = 1
    private const val TAG_OBJECT = 2

    fun encode(content: PageContent, out: OutputStream) {
        val data = DataOutputStream(out)
        data.writeInt(MAGIC)
        data.writeShort(VERSION)
        data.writeShort(0)
        data.writeInt(content.elements.size)
        val buf = ByteArrayOutputStream()
        for (element in content.elements) {
            buf.reset()
            val payload = DataOutputStream(buf)
            val tag = when (element) {
                is InkElement -> {
                    val s = element.stroke
                    payload.writeLong(element.id.value)
                    payload.writeUTF(s.brush.family.developerComment)
                    payload.writeInt(s.brush.colorIntArgb)
                    payload.writeFloat(s.brush.size)
                    payload.writeFloat(s.brush.epsilon)
                    val inputs = ByteArrayOutputStream()
                    s.inputs.encode(inputs)
                    payload.writeInt(inputs.size())
                    inputs.writeTo(payload)
                    TAG_STROKE
                }
                is ObjectElement -> {
                    payload.writeLong(element.id.value)
                    val b = element.bounds
                    payload.writeFloat(b.xMin); payload.writeFloat(b.yMin)
                    payload.writeFloat(b.xMax); payload.writeFloat(b.yMax)
                    payload.write(element.json.toByteArray(Charsets.UTF_8))
                    TAG_OBJECT
                }
            }
            payload.flush()
            data.writeByte(tag)
            data.writeInt(buf.size())
            buf.writeTo(data)
        }
        data.flush()
    }

    /** Reads v1 and v2 files. Throws [IOException] for bad magic or a version newer than [VERSION]. */
    fun decode(
        input: InputStream,
        resolveFamily: (key: String) -> BrushFamily = PenBrushes::familyForKey,
    ): List<PageElement> {
        val data = DataInputStream(input)
        if (data.readInt() != MAGIC) throw IOException("Not a SameSame notes ink layer")
        val version = data.readUnsignedShort()
        if (version > VERSION) throw IOException("Ink layer version $version is newer than this app supports")
        if (version == 1) {
            // v1 has no ids: assign fresh random ones.
            return StrokeCodec.readV1Strokes(data, resolveFamily).map { InkElement(freshId(), it) }
        }
        data.readUnsignedShort() // flags
        val count = checkedCount(data.readInt())
        // Never pre-size from the file: a short stream must fail on read, not on allocation.
        val result = ArrayList<PageElement>(minOf(count, 1024))
        repeat(count) {
            val tag = data.readUnsignedByte()
            val payload = readPayload(data, data.readInt())
            when (tag) {
                TAG_STROKE -> result += readStroke(DataInputStream(ByteArrayInputStream(payload)), resolveFamily)
                TAG_OBJECT -> result += readObject(payload)
                else -> Unit // unknown tag: skipped
            }
        }
        return result
    }

    private fun readStroke(d: DataInputStream, resolveFamily: (String) -> BrushFamily): InkElement {
        val id = ElementId(d.readLong())
        val key = d.readUTF()
        val argb = d.readInt()
        val size = d.readFloat()
        val epsilon = d.readFloat()
        val bytes = readPayload(d, d.readInt())
        val brush = Brush.createWithColorIntArgb(resolveFamily(key), argb, size, epsilon)
        return InkElement(id, Stroke(brush, StrokeInputBatch.decode(ByteArrayInputStream(bytes))))
    }

    private fun readObject(payload: ByteArray): ObjectElement {
        if (payload.size < 24) throw IOException("Truncated object element")
        val d = DataInputStream(ByteArrayInputStream(payload))
        val id = ElementId(d.readLong())
        val bounds = box(d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat())
        val json = String(payload, 24, payload.size - 24, Charsets.UTF_8)
        return ObjectElement(id, json, bounds)
    }

    private fun freshId() = ElementId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))

    /** An element count read from a file, rejected when negative or above [Limits.MAX_PAGE_ELEMENTS]. */
    internal fun checkedCount(count: Int): Int {
        if (count !in 0..Limits.MAX_PAGE_ELEMENTS) throw IOException("Ink layer declares $count elements")
        return count
    }

    /**
     * Reads a length-prefixed payload of [length] bytes. The length comes from the file, so it is checked
     * against [Limits.MAX_PAGE_ELEMENT_BYTES] and read in chunks: a truncated stream fails with an
     * [IOException] after reading what is there, never by allocating the declared size up front.
     */
    internal fun readPayload(data: DataInputStream, length: Int): ByteArray {
        if (length !in 0..Limits.MAX_PAGE_ELEMENT_BYTES) throw IOException("Ink element declares $length bytes")
        if (length <= CHUNK) return ByteArray(length).also(data::readFully)
        val out = ByteArrayOutputStream(CHUNK)
        val buf = ByteArray(CHUNK)
        var left = length
        while (left > 0) {
            val n = data.read(buf, 0, minOf(left, CHUNK))
            if (n < 0) throw java.io.EOFException("Ink element truncated")
            out.write(buf, 0, n)
            left -= n
        }
        return out.toByteArray()
    }

    private const val CHUNK = 64 * 1024
}

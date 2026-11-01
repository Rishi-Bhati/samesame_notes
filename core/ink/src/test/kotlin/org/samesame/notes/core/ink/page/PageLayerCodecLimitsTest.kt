package org.samesame.notes.core.ink.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.samesame.notes.core.ink.media.BoundedBitmaps
import org.samesame.notes.core.model.Limits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

/** Hostile counts and lengths in an ink page must fail as IOException, never as an allocation (H1). */
class PageLayerCodecLimitsTest {
    private val noFamilies: (String) -> Nothing = { error("no strokes expected") }

    private fun page(version: Int, body: DataOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0x53534E4B)
            writeShort(version)
            if (version >= 2) writeShort(0)
            body()
            flush()
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray) = PageLayerCodec.decode(ByteArrayInputStream(bytes), noFamilies)

    @Test fun `huge element count is rejected`() {
        assertThrows(IOException::class.java) { decode(page(2) { writeInt(Int.MAX_VALUE) }) }
    }

    @Test fun `negative element count is rejected`() {
        assertThrows(IOException::class.java) { decode(page(2) { writeInt(-1) }) }
    }

    @Test fun `count above the cap but plausible is rejected`() {
        assertThrows(IOException::class.java) { decode(page(2) { writeInt(Limits.MAX_PAGE_ELEMENTS + 1) }) }
    }

    @Test fun `count larger than the data fails on read`() {
        assertThrows(IOException::class.java) { decode(page(2) { writeInt(1000) }) }
    }

    @Test fun `huge element length is rejected`() {
        assertThrows(IOException::class.java) { decode(page(2) { writeInt(1); writeByte(2); writeInt(Int.MAX_VALUE) }) }
    }

    @Test fun `declared length longer than the data fails without allocating it`() {
        assertThrows(IOException::class.java) {
            decode(page(2) { writeInt(1); writeByte(2); writeInt(Limits.MAX_PAGE_ELEMENT_BYTES); write(ByteArray(10)) })
        }
    }

    @Test fun `truncated object element is rejected`() {
        assertThrows(IOException::class.java) { decode(page(2) { writeInt(1); writeByte(2); writeInt(4); writeInt(0) }) }
    }

    @Test fun `v1 huge stroke count is rejected`() {
        assertThrows(IOException::class.java) { decode(page(1) { writeInt(Int.MAX_VALUE) }) }
    }

    @Test fun `valid object element still decodes`() {
        val json = """{"k":1}""".toByteArray()
        val payload = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply {
                writeLong(7); writeFloat(1f); writeFloat(2f); writeFloat(3f); writeFloat(4f); write(json); flush()
            }
        }.toByteArray()
        val elements = decode(page(2) { writeInt(2); writeByte(2); writeInt(payload.size); write(payload); writeByte(99); writeInt(3); write(ByteArray(3)) })
        assertEquals(1, elements.size)
        val o = elements.single() as ObjectElement
        assertEquals(7L, o.id.value)
        assertEquals("""{"k":1}""", o.json)
    }

    @Test fun `bitmap sample size keeps decodes inside the pixel budget`() {
        assertEquals(1, BoundedBitmaps.sampleSize(1000, 1000, 4096 * 4096))
        assertEquals(8, BoundedBitmaps.sampleSize(20000, 20000, 4096 * 4096))
        assertEquals(0, BoundedBitmaps.sampleSize(0, 100, 4096 * 4096))
        assertEquals(0, BoundedBitmaps.sampleSize(-1, -1, 4096 * 4096))
        assertEquals(0, BoundedBitmaps.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 4096 * 4096))
    }
}

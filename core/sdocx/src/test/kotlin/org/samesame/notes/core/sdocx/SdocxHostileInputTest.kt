package org.samesame.notes.core.sdocx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.sdocx.io.LeReader
import org.samesame.notes.core.sdocx.record.LayerRecord
import org.samesame.notes.core.sdocx.record.ObjectEntry
import org.samesame.notes.core.sdocx.record.PageRecord
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Crafted .sdocx files must fail as format errors, not OutOfMemoryError / StackOverflowError (security review M1, M2). */
class SdocxHostileInputTest {

    private fun leaf(childCount: Int = 0, children: List<ObjectEntry> = emptyList()) = ObjectEntry(
        objectType = 9, childCount = childCount, record = ByteArray(8), originalHash = ByteArray(32),
        uuid = null, modifiedTime = null, children = children,
    )

    private fun nested(depth: Int): ObjectEntry {
        var e = leaf()
        repeat(depth) { e = leaf(1, listOf(e)) }
        return e
    }

    private fun withObjects(vararg objects: ObjectEntry): ByteArray {
        val a = SdocxFixtures.archive(includeUnknownObject = false)
        val p = a.pages.single()
        val l = p.layers.single()
        val layer = LayerRecord(l.flags, l.optMask, l.layerNumber, l.optionalRaw, objects.toList(), l.originalHash, l.layerId, l.modifiedTime)
        val page = PageRecord(
            p.orientation, p.width, p.height, p.offsetX, p.offsetY, p.pageId, p.modifiedTime, p.formatVersion,
            p.minFormatVersion, p.pageFlags, p.propMask, p.properties, listOf(layer), p.originalHash, p.currentLayerIndex,
        )
        return SdocxWriter().writeToBytes(SdocxArchive(a.endTag, a.note, a.pageIdInfo, listOf(page), a.media, a.mediaFiles, a.extraEntries))
    }

    @Test fun `deeply nested objects are rejected`() {
        val bytes = withObjects(nested(Limits.MAX_SDOCX_OBJECT_DEPTH + 5))
        assertThrows(SdocxException::class.java) { SdocxReader().read(bytes) }
    }

    @Test fun `nesting within the limit still reads`() {
        val archive = SdocxReader().read(withObjects(nested(3)))
        assertEquals(1, archive.pages.single().layers.single().objects.size)
    }

    @Test fun `child count larger than the data is rejected before allocating`() {
        val bytes = withObjects(leaf(childCount = 65_535))
        assertThrows(Exception::class.java) { SdocxReader().read(bytes) }
    }

    @Test fun `record counts are checked against the bytes left`() {
        val r = LeReader(ByteArray(100))
        assertEquals(0, r.count(-5, 4))
        assertEquals(25, r.count(25, 4))
        assertThrows(IllegalArgumentException::class.java) { r.count(26, 4) }
        assertThrows(IllegalArgumentException::class.java) { r.count(Int.MAX_VALUE, 39) }
    }

    @Test fun `overflowing lengths are rejected`() {
        val r = LeReader(ByteArray(16))
        r.pos = 8
        assertThrows(IllegalArgumentException::class.java) { r.bytes(Int.MAX_VALUE) }
    }

    @Test fun `zip bomb entry is rejected`() {
        // A small archive whose one entry inflates far beyond the allowed ratio.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("note.note"))
            val zeros = ByteArray(1 shl 20)
            repeat(Limits.MAX_ARCHIVE_INFLATE_SLACK_BYTES.toInt() / zeros.size + 16) { zip.write(zeros) }
            zip.closeEntry()
        }
        assertThrows(SdocxFormatException::class.java) { SdocxReader().read(out.toByteArray()) }
    }
}

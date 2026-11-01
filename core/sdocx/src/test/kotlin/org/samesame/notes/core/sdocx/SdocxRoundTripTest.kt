package org.samesame.notes.core.sdocx

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.samesame.notes.core.sdocx.record.EndTagRecord
import org.samesame.notes.core.sdocx.record.LayerRecord
import org.samesame.notes.core.sdocx.record.ObjectEntry
import org.samesame.notes.core.sdocx.record.PageRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SdocxRoundTripTest {

    @Test
    fun `writer to reader to writer is byte identical`() {
        val first = SdocxWriter().writeToBytes(SdocxFixtures.archive())
        val archive = SdocxReader().read(first)
        val second = SdocxWriter().writeToBytes(archive)

        assertEntriesEqual(zipEntries(second), zipEntries(first))
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `doc page and layer hashes are recomputed from the spec formulas`() {
        val bytes = SdocxWriter().writeToBytes(SdocxFixtures.archive())
        val entries = zipEntries(bytes)
        val archive = SdocxReader().read(bytes)

        val note = entries.getValue("note.note")
        val docHash = note.copyOfRange(note.size - 32, note.size)
        assertThat(docHash).isEqualTo(sha256(note.copyOfRange(0, note.size - 32)))
        assertThat(archive.note.docHash).isEqualTo(docHash)

        val pageIdInfo = entries.getValue("pageIdInfo.dat")
        assertThat(pageIdInfo.copyOfRange(0, 32)).isEqualTo(docHash)
        // u16 count follows the 32-byte doc hash.
        assertThat(pageIdInfo[32].toInt() or (pageIdInfo[33].toInt() shl 8)).isEqualTo(1)

        val page = archive.pages.single()
        assertThat(page.originalHash).isEqualTo(archive.pageIdInfo.pages.single().pageHash)

        val layer = page.layers.single()
        val objectHashes = layer.objects.map { obj ->
            if (obj.uuid != null) objectHash(obj.uuid, obj.modifiedTime!!) else obj.originalHash
        }
        val layerHash = sha256(concat(objectHashes) + sha256("${layer.layerId}${layer.modifiedTime}"))
        assertThat(layer.originalHash).isEqualTo(layerHash)

        val pageHash = sha256(
            concat(listOf(layerHash)) + sha256("${page.pageId}${page.modifiedTime}"),
        )
        assertThat(page.originalHash).isEqualTo(pageHash)

        // The hash is embedded in the page file: 32 bytes just before the 26-byte magic.
        val pageBytes = entries.getValue("${page.pageId}.page")
        assertThat(pageBytes.copyOfRange(pageBytes.size - 58, pageBytes.size - 26))
            .isEqualTo(pageHash)
    }

    @Test
    fun `timestamps keep microsecond precision`() {
        val archive = SdocxReader().read(SdocxWriter().writeToBytes(SdocxFixtures.archive()))
        assertThat(archive.note.createdTime).isEqualTo(SdocxFixtures.CREATED_US)
        assertThat(archive.note.modifiedTime).isEqualTo(SdocxFixtures.MODIFIED_US)
        assertThat(archive.endTag.createdTime).isEqualTo(SdocxFixtures.CREATED_US)
        assertThat(archive.endTag.modifiedTime).isEqualTo(SdocxFixtures.MODIFIED_US)
        assertThat(archive.endTag.displayModifiedTime).isEqualTo(SdocxFixtures.MODIFIED_US)
        assertThat(archive.pages.single().modifiedTime).isEqualTo(SdocxFixtures.MODIFIED_US)
        assertThat(archive.pages.single().layers.single().modifiedTime)
            .isEqualTo(SdocxFixtures.MODIFIED_US)
        assertThat(archive.pages.single().layers.single().objects.first().modifiedTime)
            .isEqualTo(SdocxFixtures.MODIFIED_US)
        assertThat(archive.note.modifiedTime).isGreaterThan(1_000_000_000_000L)
    }

    @Test
    fun `unknown object and stroke payload pass through untouched`() {
        val first = SdocxWriter().writeToBytes(SdocxFixtures.archive())
        val archive = SdocxReader().read(first)
        val layer = archive.pages.single().layers.single()

        val stroke = layer.objects[0]
        assertThat(stroke.objectType).isEqualTo(SdocxFormat.ObjectType.STROKE)
        assertThat(stroke.record).isEqualTo(
            SdocxFixtures.strokeRecord(SdocxFixtures.UUID_STROKE, SdocxFixtures.MODIFIED_US),
        )

        val unknown = layer.objects[1]
        assertThat(unknown.objectType).isEqualTo(9)
        assertThat(unknown.uuid).isNull()
        assertThat(unknown.record).isEqualTo(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertThat(unknown.originalHash).isEqualTo(ByteArray(32) { (it + 1).toByte() })

        val second = SdocxWriter().writeToBytes(archive)
        val reread = SdocxReader().read(second)
        val rereadUnknown = reread.pages.single().layers.single().objects[1]
        assertThat(rereadUnknown.record).isEqualTo(unknown.record)
        assertThat(rereadUnknown.originalHash).isEqualTo(unknown.originalHash)
    }

    @Test
    fun `locked note imports without a password and keeps its flag and owner`() {
        val bytes = SdocxWriter().writeToBytes(SdocxFixtures.archive(locked = true))
        val archive = SdocxReader().read(bytes)
        assertThat(archive.isLocked).isTrue()
        assertThat(archive.ownerId).isEqualTo(SdocxFixtures.ACCOUNT_ID)
        assertThat(archive.endTag.documentType).isEqualTo(SdocxFormat.DocumentType.LOCKED_SDOCX)
        // Content is plaintext: the body is readable without any password.
        assertThat(archive.note.body.text?.text).isEqualTo("Hello sdocx\nSecond line")

        val rewrite = SdocxReader().read(SdocxWriter().writeToBytes(archive))
        assertThat(rewrite.isLocked).isTrue()
        assertThat(rewrite.ownerId).isEqualTo(SdocxFixtures.ACCOUNT_ID)
    }

    @Test
    fun `legacy sdoc archive is detected and rejected`() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("doc.dat")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
            z.putNextEntry(ZipEntry("content.dat")); z.write(byteArrayOf(4, 5)); z.closeEntry()
            z.putNextEntry(ZipEntry("endtag.dat")); z.write(byteArrayOf(6)); z.closeEntry()
        }
        assertThrows(SdocxLegacyFormatException::class.java) {
            SdocxReader().read(zip.toByteArray())
        }
    }

    @Test
    fun `version below 2034 is treated as legacy`() {
        val base = SdocxFixtures.archive()
        val legacyTag = EndTagRecord(
            formatVersion = 2033,
            noteId = base.endTag.noteId,
            modifiedTime = base.endTag.modifiedTime,
            propertyFlags = 0,
            coverImage = "",
            noteWidth = 2000,
            noteHeight = 5700f,
            appName = "",
            appMajorVersion = -1,
            appMinorVersion = -1,
            appPatchName = "",
            minFormatVersion = 2033,
            createdTime = base.endTag.createdTime,
            lastViewedPageIndex = 0,
            pageMode = 0,
            documentType = 0,
            ownerId = "",
            reserved1 = ByteArray(0),
            reserved2 = ByteArray(0),
            displayCreatedTime = base.endTag.displayCreatedTime,
            displayModifiedTime = base.endTag.displayModifiedTime,
            lastRecognizedDataModifiedTime = 0,
            fixedFont = "",
            fixedTextDirection = 2,
            fixedBackgroundTheme = 2,
            hasTail = true,
        )
        val archive = SdocxArchive(
            endTag = legacyTag,
            note = base.note,
            pageIdInfo = base.pageIdInfo,
            pages = base.pages,
            media = base.media,
            mediaFiles = base.mediaFiles,
            extraEntries = emptyMap(),
        )
        val bytes = SdocxWriter().writeToBytes(archive)
        val error = assertThrows(SdocxLegacyFormatException::class.java) {
            SdocxReader().read(bytes)
        }
        assertThat(error.detectedFormatVersion).isEqualTo(2033)
    }

    @Test
    fun `editing object identity recomputes object layer and page hashes`() {
        val archive = SdocxReader().read(SdocxWriter().writeToBytes(SdocxFixtures.archive()))
        val page = archive.pages.single()
        val layer = page.layers.single()
        val changedStroke = layer.objects.first().withIdentity(
            SdocxFixtures.UUID_STROKE,
            SdocxFixtures.MODIFIED_US + 1,
        )
        val changedLayer = LayerRecord(
            flags = layer.flags,
            optMask = layer.optMask,
            layerNumber = layer.layerNumber,
            optionalRaw = layer.optionalRaw,
            objects = listOf(changedStroke, layer.objects[1]),
            originalHash = layer.originalHash,
            layerId = layer.layerId,
            modifiedTime = layer.modifiedTime,
        )
        val changedPage = PageRecord(
            orientation = page.orientation,
            width = page.width,
            height = page.height,
            offsetX = page.offsetX,
            offsetY = page.offsetY,
            pageId = page.pageId,
            modifiedTime = page.modifiedTime,
            formatVersion = page.formatVersion,
            minFormatVersion = page.minFormatVersion,
            pageFlags = page.pageFlags,
            propMask = page.propMask,
            properties = page.properties,
            layers = listOf(changedLayer),
            originalHash = page.originalHash,
            currentLayerIndex = page.currentLayerIndex,
        )
        val changed = SdocxArchive(
            endTag = archive.endTag,
            note = archive.note,
            pageIdInfo = archive.pageIdInfo,
            pages = listOf(changedPage),
            media = archive.media,
            mediaFiles = archive.mediaFiles,
            extraEntries = emptyMap(),
        )

        val reread = SdocxReader().read(SdocxWriter().writeToBytes(changed))
        val rePage = reread.pages.single()
        val reLayer = rePage.layers.single()
        val expectedObjectHash = objectHash(SdocxFixtures.UUID_STROKE, SdocxFixtures.MODIFIED_US + 1)
        val expectedLayerHash = sha256(
            concat(listOf(expectedObjectHash, reLayer.objects[1].originalHash)) +
                sha256("${reLayer.layerId}${reLayer.modifiedTime}"),
        )
        assertThat(reLayer.originalHash).isEqualTo(expectedLayerHash)
        assertThat(rePage.originalHash).isEqualTo(
            sha256(concat(listOf(expectedLayerHash)) + sha256("${rePage.pageId}${rePage.modifiedTime}")),
        )
        assertThat(rePage.originalHash).isNotEqualTo(page.originalHash)
        // The record bytes themselves are untouched by a timestamp change.
        assertThat(reLayer.objects.first().record).isEqualTo(layer.objects.first().record)
    }

    @Test
    fun `media registry and media file survive round trip`() {
        val archive = SdocxReader().read(SdocxWriter().writeToBytes(SdocxFixtures.archive()))
        assertThat(archive.media.extended).isFalse()
        val entry = archive.media.entries.single()
        assertThat(entry.bindNumber).isEqualTo(0)
        assertThat(entry.fileName).isEqualTo("pic.png")
        assertThat(entry.fileHash).isEqualTo("aa".repeat(32))
        assertThat(entry.refCount).isEqualTo(1)
        assertThat(archive.mediaFiles.getValue("media/pic.png")).isEqualTo(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3),
        )
    }

    private fun assertEntriesEqual(actual: Map<String, ByteArray>, expected: Map<String, ByteArray>) {
        assertThat(actual.keys).isEqualTo(expected.keys)
        for ((name, expectedBytes) in expected) {
            assertThat(actual.getValue(name)).isEqualTo(expectedBytes)
        }
    }

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return out
    }
}

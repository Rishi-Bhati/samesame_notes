package org.samesame.notes.core.sdocx

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes an [SdocxArchive] back to a `.sdocx` ZIP with **full round-trip fidelity**:
 * every object record and property block is re-emitted from its raw bytes, while all derived
 * values (object/layer/page hashes, document hash, container offsets, length prefixes) are
 * recomputed per `sdocx-format-spec.md` §12. Timestamps are kept in microseconds.
 */
class SdocxWriter {

    fun write(archive: SdocxArchive, file: File) {
        file.writeBytes(writeToBytes(archive))
    }

    fun write(archive: SdocxArchive, out: OutputStream) {
        out.write(writeToBytes(archive))
        out.flush()
    }

    fun writeToBytes(archive: SdocxArchive): ByteArray {
        val serializedPages = archive.pages.map { SdocxSerializers.page(it) }
        val pageHashes = archive.pages.mapIndexed { i, p -> p.pageId to serializedPages[i].hash }

        val noteBytes = SdocxSerializers.note(archive.note)
        val docHash = noteBytes.copyOfRange(noteBytes.size - 32, noteBytes.size)

        val pageIdInfoBytes = SdocxSerializers.pageIdInfo(docHash, pageHashes)
        val mediaInfoBytes = SdocxSerializers.mediaInfo(archive.media)
        val endTagBytes = SdocxSerializers.endTag(archive.endTag)

        val zipOut = ByteArrayOutputStream()
        ZipOutputStream(zipOut).use { zip ->
            zip.putNextEntry(entry(SdocxFormat.NOTE_TITLE_PREFIX))
            zip.write(noteBytes)
            zip.closeEntry()

            archive.pages.forEachIndexed { i, page ->
                zip.putNextEntry(entry("${page.pageId}.page"))
                zip.write(serializedPages[i].bytes)
                zip.closeEntry()
            }

            zip.putNextEntry(entry(SdocxFormat.NOTE_PAGE_ID_INFO))
            zip.write(pageIdInfoBytes)
            zip.closeEntry()

            zip.putNextEntry(entry(SdocxFormat.NOTE_MEDIA_INFO))
            zip.write(mediaInfoBytes)
            zip.closeEntry()

            for ((name, bytes) in archive.mediaFiles) {
                zip.putNextEntry(entry(name))
                zip.write(bytes)
                zip.closeEntry()
            }

            zip.putNextEntry(entry(SdocxFormat.NOTE_END_TAG))
            zip.write(endTagBytes)
            zip.closeEntry()

            for ((name, bytes) in archive.extraEntries) {
                zip.putNextEntry(entry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.finish()
        }
        return zipOut.toByteArray()
    }

    private fun entry(name: String): ZipEntry = ZipEntry(name).apply {
        // Fixed timestamp keeps writer output deterministic (needed for byte-identical tests).
        time = 0L
        setExtra(ByteArray(0))
    }

    private companion object
}

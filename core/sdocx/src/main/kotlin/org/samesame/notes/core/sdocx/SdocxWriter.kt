package org.samesame.notes.core.sdocx

import java.io.File
import java.io.OutputStream

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

        // Entry order, methods and header flags follow files written by the reference app: page index
        // first, pages, media registry, media (stored), note, end tag; sizes and CRC in the local
        // header (no data descriptor), which a sequential reader needs.
        val zip = SdocxZip(dosTimeOf(archive.endTag.modifiedTime))
        zip.add(SdocxFormat.NOTE_PAGE_ID_INFO, pageIdInfoBytes)
        archive.pages.forEachIndexed { i, page -> zip.add("${page.pageId}.page", serializedPages[i].bytes) }
        zip.add(SdocxFormat.NOTE_MEDIA_INFO, mediaInfoBytes)
        for ((name, bytes) in archive.mediaFiles) zip.add(name, bytes, stored = true)
        zip.add(SdocxFormat.NOTE_TITLE_PREFIX, noteBytes)
        zip.add(SdocxFormat.NOTE_END_TAG, endTagBytes)
        for ((name, bytes) in archive.extraEntries) zip.add(name, bytes)
        val zipBytes = zip.finish()
        val trailer = SdocxSerializers.endTag(trailerOf(archive))
        return zipBytes + trailer
    }

    /**
     * The end tag appended after the ZIP: the current end tag with its display times in milliseconds.
     * The source's own trailer values are kept while the note is unmodified.
     */
    private fun trailerOf(archive: SdocxArchive): org.samesame.notes.core.sdocx.record.EndTagRecord {
        val tag = archive.endTag
        val source = archive.trailer
        val created = source?.displayCreatedTime ?: millis(tag.displayCreatedTime)
        val modified = if (source != null && source.modifiedTime == tag.modifiedTime) source.displayModifiedTime else millis(tag.displayModifiedTime)
        return SdocxMirror.withDisplayTimes(tag, created, modified)
    }

    private fun millis(stamp: Long): Long = if (kotlin.math.abs(stamp) > 100_000_000_000_000L) stamp / 1000L else stamp

    /** The note's modified time (µs or ms, core spec §0.1) as a UTC DOS date/time pair; deterministic per note. */
    private fun dosTimeOf(stamp: Long): Long {
        val ms = if (kotlin.math.abs(stamp) > 100_000_000_000_000L) stamp / 1000L else stamp
        val t = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC)
        if (t.year < 1980 || t.year > 2107) return (1L shl 21) or (1L shl 16) // 1980-01-01 00:00
        val date = ((t.year - 1980) shl 9) or (t.monthValue shl 5) or t.dayOfMonth
        val time = (t.hour shl 11) or (t.minute shl 5) or (t.second / 2)
        return (date.toLong() shl 16) or time.toLong()
    }

    private companion object
}

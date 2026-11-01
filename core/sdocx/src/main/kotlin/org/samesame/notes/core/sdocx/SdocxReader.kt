package org.samesame.notes.core.sdocx

import org.samesame.notes.core.sdocx.io.LeReader
import org.samesame.notes.core.sdocx.io.SizedInt
import org.samesame.notes.core.sdocx.record.EndTagRecord
import org.samesame.notes.core.sdocx.record.LayerRecord
import org.samesame.notes.core.sdocx.record.MediaEntry
import org.samesame.notes.core.sdocx.record.MediaRegistry
import org.samesame.notes.core.sdocx.record.NotePropertyBlock
import org.samesame.notes.core.sdocx.record.NoteRecord
import org.samesame.notes.core.sdocx.record.ObjectEntry
import org.samesame.notes.core.sdocx.record.PageIdInfo
import org.samesame.notes.core.sdocx.record.PagePropertyBlock
import org.samesame.notes.core.sdocx.record.PageRecord
import org.samesame.notes.core.sdocx.record.PdfPlacement
import org.samesame.notes.core.sdocx.record.PenInfo
import org.samesame.notes.core.sdocx.record.RectF64
import org.samesame.notes.core.sdocx.record.TextBoxRecord
import org.samesame.notes.core.sdocx.record.TextCommon
import org.samesame.notes.core.sdocx.record.VoiceDataRecord
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.io.LimitedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Reads a `.sdocx` (ZIP) into an [SdocxArchive]. Everything the spec describes is parsed; every
 * opaque or undelimited unit is also kept as raw bytes for lossless round-trip.
 *
 * Locked `.sdocx` notes are **not encrypted** (spec §9.1): they parse like any other note and
 * [SdocxArchive.isLocked] / [SdocxArchive.ownerId] carry the flag. Legacy `.sdoc` files are
 * detected and rejected with [SdocxLegacyFormatException].
 */
class SdocxReader {

    private companion object {
        /** Smallest object entry: u8 type, u16 childCount, i32 recordLen, 32-byte hash. */
        const val OBJECT_ENTRY_MIN_BYTES = 39
    }

    fun read(file: File): SdocxArchive = read(file.readBytes())

    fun read(bytes: ByteArray): SdocxArchive {
        val entries = unzip(bytes)
        val endTag = parseEndTag(entries.named(SdocxFormat.NOTE_END_TAG))
        gateVersion(endTag.formatVersion, endTag.minFormatVersion)
        val note = parseNote(entries.named(SdocxFormat.NOTE_TITLE_PREFIX), endTag)
        val pageIdInfo = parsePageIdInfo(entries.named(SdocxFormat.NOTE_PAGE_ID_INFO))
        val pages = pageIdInfo.pages.map { entry ->
            val name = "${entry.pageId}.page"
            parsePage(entries.named(name), entry.pageId)
        }
        val media = parseMedia(entries.named(SdocxFormat.NOTE_MEDIA_INFO))
        val mediaFiles = entries.filterKeys { it.startsWith("media/") && it != SdocxFormat.NOTE_MEDIA_INFO }
        val extra = entries.filterKeys { name ->
            name != SdocxFormat.NOTE_END_TAG &&
                name != SdocxFormat.NOTE_TITLE_PREFIX &&
                name != SdocxFormat.NOTE_PAGE_ID_INFO &&
                name != SdocxFormat.NOTE_MEDIA_INFO &&
                !name.endsWith(".page") &&
                !name.startsWith("media/")
        }
        return SdocxArchive(endTag, note, pageIdInfo, pages, media, mediaFiles, extra)
    }

    // ---- container ----

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        // Entry sizes in ZIP headers are not trusted: every entry is inflated under a cap, and so is the total.
        var total = 0L
        var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > Limits.MAX_ARCHIVE_ENTRIES) throw SdocxFormatException("too many ZIP entries")
                if (entry.isDirectory) continue
                val limit = minOf(Limits.MAX_SDOCX_INFLATED_BYTES, Limits.maxInflatedBytes(bytes.size.toLong())) - total
                val data = try {
                    LimitedInputStream(zip, limit, entry.name, closeInput = false).readBytes()
                } catch (e: org.samesame.notes.core.model.io.InputTooLargeException) {
                    throw SdocxFormatException("entry ${entry.name} is too large")
                }
                total += data.size
                out[entry.name] = data
                zip.closeEntry()
            }
        }
        if (out.isEmpty()) throw SdocxFormatException("not a .sdocx archive (no ZIP entries)")
        if (!out.containsKey(SdocxFormat.NOTE_TITLE_PREFIX)) {
            val looksLegacy = out.keys.any { it.endsWith(".dat") && it != SdocxFormat.NOTE_PAGE_ID_INFO } ||
                out.keys.any { it == "doc.dat" || it == "content.dat" || it == "endtag.dat" }
            throw SdocxLegacyFormatException(
                null,
                if (looksLegacy) "legacy note format is not supported (device-bound key)"
                else "missing note.note; not a .sdocx doc",
            )
        }
        return out
    }

    private fun LinkedHashMap<String, ByteArray>.named(name: String): ByteArray =
        this[name] ?: throw SdocxFormatException("missing required entry $name")

    private fun gateVersion(formatVersion: Int, minFormatVersion: Int) {
        if (formatVersion < SdocxFormat.MIN_SUPPORTED_VERSION || minFormatVersion < SdocxFormat.MIN_SUPPORTED_VERSION) {
            throw SdocxLegacyFormatException(
                formatVersion,
                "legacy note (formatVersion $formatVersion < ${SdocxFormat.MIN_SUPPORTED_VERSION}); not supported",
            )
        }
        if (formatVersion > SdocxFormat.MAX_SUPPORTED_VERSION || minFormatVersion > SdocxFormat.MAX_SUPPORTED_VERSION) {
            throw SdocxFormatException(
                "unsupported .sdocx version $formatVersion (max ${SdocxFormat.MAX_SUPPORTED_VERSION})",
            )
        }
    }

    // ---- end_tag.bin (§2) ----

    private fun parseEndTag(data: ByteArray): EndTagRecord {
        if (data.size < 24) throw SdocxFormatException("end_tag.bin too short (${data.size} bytes)")
        val magic = String(data, data.size - 22, 22, Charsets.US_ASCII)
        if (magic != SdocxFormat.END_TAG_MAGIC) {
            throw SdocxFormatException("bad end_tag magic: '$magic'")
        }
        val r = LeReader(data)
        val bodyLen = r.u16()
        val limit = (bodyLen - 22).coerceAtMost(data.size - 22)
        val formatVersion = r.i32()
        val noteId = r.str16() ?: ""
        val modifiedTime = r.i64()
        val propertyFlags = r.i32()
        val coverImage = r.str16() ?: ""
        val noteWidth = r.i32()
        val noteHeight = r.f32()
        val appName = r.str16() ?: ""
        val appMajor = r.i32()
        val appMinor = r.i32()
        val appPatch = r.str16() ?: ""
        val minFormatVersion = r.i32()
        val createdTime = r.i64()
        val lastViewedPageIndex = r.i32()
        val pageMode = r.u16()

        var hasTail = false
        var documentType = SdocxFormat.DocumentType.UNLOCKED
        var ownerId = ""
        var reserved1 = ByteArray(0)
        var reserved2 = ByteArray(0)
        var displayCreated = createdTime
        var displayModified = modifiedTime
        var lastRecognized = 0L
        var fixedFont = ""
        var fixedDir = 2
        var fixedTheme = 2

        if (r.pos < limit) {
            hasTail = true
            documentType = r.u16()
            ownerId = r.str16() ?: ""
            reserved1 = r.bytes(r.i32().coerceAtLeast(0))
            reserved2 = r.bytes(r.i32().coerceAtLeast(0))
            displayCreated = r.i64()
            displayModified = r.i64()
            lastRecognized = r.i64()
        }
        if (r.pos < limit) {
            fixedFont = r.str16() ?: ""
            fixedDir = r.i32()
            fixedTheme = r.i32()
        }

        return EndTagRecord(
            formatVersion = formatVersion,
            noteId = noteId,
            modifiedTime = modifiedTime,
            propertyFlags = propertyFlags,
            coverImage = coverImage,
            noteWidth = noteWidth,
            noteHeight = noteHeight,
            appName = appName,
            appMajorVersion = appMajor,
            appMinorVersion = appMinor,
            appPatchName = appPatch,
            minFormatVersion = minFormatVersion,
            createdTime = createdTime,
            lastViewedPageIndex = lastViewedPageIndex,
            pageMode = pageMode,
            documentType = documentType,
            ownerId = ownerId,
            reserved1 = reserved1,
            reserved2 = reserved2,
            displayCreatedTime = displayCreated,
            displayModifiedTime = displayModified,
            lastRecognizedDataModifiedTime = lastRecognized,
            fixedFont = fixedFont,
            fixedTextDirection = fixedDir,
            fixedBackgroundTheme = fixedTheme,
            hasTail = hasTail,
        )
    }

    // ---- note.note (§3) ----

    private fun parseNote(data: ByteArray, endTag: EndTagRecord): NoteRecord {
        if (data.size < 46) throw SdocxFormatException("note.note too short")
        val r = LeReader(data)
        val propOffset = r.i32()
        val noteFlags = r.sizedInt()
        val propMask = r.sizedInt()
        val formatVersion = r.i32()
        val noteId = r.str16() ?: ""
        val fileRevision = r.i32()
        val createdTime = r.i64()
        val modifiedTime = r.i64()
        val width = r.i32()
        val height = r.i32()
        val pageHorizontalPadding = r.i32()
        val pageVerticalPadding = r.i32()
        val minFormatVersion = r.i32()

        val title = readTextBox(r)
        val body = readTextBox(r)

        if (propOffset < r.pos || propOffset > data.size - 32) {
            throw SdocxFormatException("bad note propOffset $propOffset")
        }
        val propsEnd = data.size - 32
        val propsRaw = data.copyOfRange(propOffset, propsEnd)
        val properties = parseNotePropertyBlock(propsRaw, propMask.value.toInt())
        val docHash = data.copyOfRange(propsEnd, data.size)

        gateVersion(formatVersion, minFormatVersion)

        return NoteRecord(
            propOffset = propOffset,
            noteFlags = noteFlags,
            propMask = propMask.value.toInt(),
            formatVersion = formatVersion,
            noteId = noteId,
            fileRevision = fileRevision,
            createdTime = createdTime,
            modifiedTime = modifiedTime,
            width = width,
            height = height,
            pageHorizontalPadding = pageHorizontalPadding,
            pageVerticalPadding = pageVerticalPadding,
            minFormatVersion = minFormatVersion,
            title = title,
            body = body,
            properties = properties,
            docHash = docHash,
        )
    }

    private fun readTextBox(r: LeReader): TextBoxRecord {
        val len = r.i32()
        if (len <= 0) return TextBoxRecord(ByteArray(0), null)
        val record = r.bytes(len)
        return TextBoxRecord(record, parseTextCommonFromTextBox(record))
    }

    // ---- pageIdInfo.dat (§4) ----

    private fun parsePageIdInfo(data: ByteArray): PageIdInfo {
        if (data.size < 34) throw SdocxFormatException("pageIdInfo.dat too short")
        val r = LeReader(data)
        val docHash = r.bytes(32)
        val count = r.u16()
        val pages = ArrayList<PageIdInfo.Entry>(count)
        repeat(count) {
            val id = r.str16() ?: ""
            val hash = r.bytes(32)
            pages += PageIdInfo.Entry(id, hash)
        }
        return PageIdInfo(docHash, pages)
    }

    // ---- page file (§5) ----

    private fun parsePage(data: ByteArray, expectedPageId: String): PageRecord {
        val r = LeReader(data)
        val layerListOffset = r.i32()
        val propOffset = r.i32()
        val pageFlags = r.sizedInt()
        val propMask = r.sizedInt()
        val orientation = r.i32()
        val width = r.i32()
        val height = r.i32()
        val offsetX = r.i32()
        val offsetY = r.i32()
        val pageId = r.str16() ?: expectedPageId
        val modifiedTime = r.i64()
        val formatVersion = r.i32()
        val minFormatVersion = r.i32()

        gateVersion(formatVersion, minFormatVersion)

        if (propOffset < r.pos || propOffset > layerListOffset || layerListOffset > data.size - 32) {
            throw SdocxFormatException("bad page offsets (prop=$propOffset layer=$layerListOffset size=${data.size})")
        }
        val propsRaw = data.copyOfRange(propOffset, layerListOffset)
        val properties = parsePagePropertyBlock(propsRaw, propMask.value.toInt(), formatVersion)

        r.seek(layerListOffset)
        val layerCount = r.u16()
        val currentLayerIndex = r.u16()
        val layers = ArrayList<LayerRecord>(layerCount)
        repeat(layerCount) { layers += parseLayer(r, data) }

        val pageHash = if (r.remaining >= 32) r.bytes(32) else ByteArray(32)
        return PageRecord(
            orientation = orientation,
            width = width,
            height = height,
            offsetX = offsetX,
            offsetY = offsetY,
            pageId = pageId,
            modifiedTime = modifiedTime,
            formatVersion = formatVersion,
            minFormatVersion = minFormatVersion,
            pageFlags = pageFlags,
            propMask = propMask.value.toInt(),
            properties = properties,
            layers = layers,
            originalHash = pageHash,
            currentLayerIndex = currentLayerIndex,
        )
    }

    private fun parseLayer(r: LeReader, data: ByteArray): LayerRecord {
        val layerStart = r.pos
        val headerSize = r.i32()
        val optAbsOffset = r.i32()
        val flags = r.sizedInt().value.toInt()
        val optMask = r.sizedInt().value.toInt()
        val layerNumber = r.i32()
        val optStart = layerStart + 16
        val optEnd = layerStart + headerSize
        if (headerSize < 16 || optEnd > data.size) {
            throw SdocxFormatException("bad layer headerSize $headerSize")
        }
        val optionalRaw = data.copyOfRange(optStart, optEnd)

        val (layerId, layerModifiedTime) = parseLayerOptional(optionalRaw, optMask)

        r.seek(optEnd)
        val objectCount = r.count(r.i32(), OBJECT_ENTRY_MIN_BYTES)
        val objects = ArrayList<ObjectEntry>(objectCount)
        repeat(objectCount) { objects += parseObjectEntry(r, depth = 0) }
        val layerHash = r.bytes(32)

        // `optAbsOffset` is absolute and must be re-patched on write (spec §6.1); the reader relies
        // on `headerSize` instead, so a mismatch there only matters for relocation.
        return LayerRecord(
            flags = flags,
            optMask = optMask,
            layerNumber = layerNumber,
            optionalRaw = optionalRaw,
            objects = objects,
            originalHash = layerHash,
            layerId = layerId,
            modifiedTime = layerModifiedTime,
        )
    }

    private fun parseLayerOptional(raw: ByteArray, mask: Int): Pair<String?, Long?> {
        var id: String? = null
        var time: Long? = null
        if (mask != 0) {
            val r = LeReader(raw)
            // bit 0 transparency is width-ambiguous; the writer never sets it.
            if (mask and 0x1 != 0) r.i32()
            if (mask and 0x2 != 0) r.i32() // layer background colour
            if (mask and 0x4 != 0) r.str16() // layer name
            if (mask and 0x8 != 0) id = r.str16()
            if (mask and 0x10 != 0) time = r.i64()
            if (mask and 0x20 != 0) r.i32() // thumbnail id
        }
        return id to time
    }

    private fun parseObjectEntry(r: LeReader, depth: Int): ObjectEntry {
        // Grouped objects nest; a hostile page could nest deep enough to overflow the stack.
        if (depth >= Limits.MAX_SDOCX_OBJECT_DEPTH) throw SdocxFormatException("objects nested deeper than ${Limits.MAX_SDOCX_OBJECT_DEPTH}")
        val objectType = r.u8()
        val childCount = r.u16()
        val recordLen = r.i32()
        if (recordLen < 32 || recordLen > 2 * 1024 * 1024) {
            throw SdocxFormatException("bad object recordLen $recordLen")
        }
        val full = r.bytes(recordLen)
        val record = full.copyOfRange(0, full.size - 32)
        val hash = full.copyOfRange(full.size - 32, full.size)
        val base = parseObjectBase(record)
        val children = ArrayList<ObjectEntry>(r.count(childCount, OBJECT_ENTRY_MIN_BYTES))
        repeat(childCount) { children += parseObjectEntry(r, depth + 1) }
        return ObjectEntry(
            objectType = objectType,
            childCount = childCount,
            record = record,
            originalHash = hash,
            uuid = base?.uuid,
            modifiedTime = base?.modifiedTime,
            children = children,
        )
    }

    // ---- media/mediaInfo.dat (§8) ----

    private fun parseMedia(data: ByteArray): MediaRegistry {
        val extended = data.size >= 4 && String(data, data.size - 4, 4, Charsets.US_ASCII) == "EOFX"
        val r = LeReader(data)
        val formatVersion = if (extended) r.i32() else SdocxFormat.WRITE_FORMAT_VERSION
        val count = r.u16()
        val entries = ArrayList<MediaEntry>(count)
        repeat(count) {
            if (extended) r.i32() // entryLen
            val bindNumber = r.i32()
            val fileName = r.str16() ?: ""
            val hashBytes = r.bytes(64)
            val fileHash = String(hashBytes, Charsets.US_ASCII)
            val refCount = r.u16()
            val modifiedTime = r.i64()
            val attached = if (extended) r.u8() == 1 else null
            entries += MediaEntry(bindNumber, fileName, fileHash, refCount, modifiedTime, attached)
        }
        val hasTerminator = r.remaining >= 3 &&
            String(data, data.size - if (extended) 4 else 3, if (extended) 4 else 3, Charsets.US_ASCII)
                .let { it == "EOF" || it == "EOFX" }
        return MediaRegistry(extended, formatVersion, entries, hasTerminator)
    }
}

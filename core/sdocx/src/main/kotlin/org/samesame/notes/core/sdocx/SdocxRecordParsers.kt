package org.samesame.notes.core.sdocx

import org.samesame.notes.core.sdocx.io.LeReader
import org.samesame.notes.core.sdocx.record.NotePropertyBlock
import org.samesame.notes.core.sdocx.record.PagePropertyBlock
import org.samesame.notes.core.sdocx.record.PdfPlacement
import org.samesame.notes.core.sdocx.record.PenInfo
import org.samesame.notes.core.sdocx.record.RectF64
import org.samesame.notes.core.sdocx.record.TextCommon
import org.samesame.notes.core.sdocx.record.VoiceDataRecord

internal data class BaseInfo(val uuid: String, val modifiedTime: Long, val objFormatVersion: Int)

private fun argb(r: LeReader): Long = r.i32().toLong() and 0xFFFFFFFFL

/**
 * Parses an object-base block (tag 0, objects spec §2) for the uuid, modified time and format
 * version. Returns null when the record does not start with a base block (e.g. legacy strokes).
 */
internal fun parseObjectBase(record: ByteArray): BaseInfo? {
    if (record.size < 68) return null
    return try {
        val r = LeReader(record)
        val blockSize = r.i32()
        if (blockSize <= 0 || blockSize > record.size) return null
        val tag = r.i16()
        if (tag != 0) return null
        r.i32() // optOffset
        r.sizedInt() // attrFlags
        r.sizedInt() // optMask
        val version = r.i32()
        val uuid = r.uuid36()
        val modifiedTime = r.i64()
        BaseInfo(uuid, modifiedTime, version)
    } catch (_: Exception) {
        null
    }
}

/**
 * Extracts the text-common block from a text-box object record by walking its class-block chain
 * (base → shapeBase → shape → textBox) and reading shape optional bit 0 (objects spec §3.2, §6).
 */
internal fun parseTextCommonFromTextBox(record: ByteArray): TextCommon? {
    var objFormatVersion = SdocxFormat.WRITE_FORMAT_VERSION
    var textCommon: TextCommon? = null
    var pos = 0
    while (pos + 10 <= record.size) {
        val r = LeReader(record, pos)
        val blockSize = try {
            r.i32()
        } catch (_: Exception) {
            break
        }
        if (blockSize <= 0 || pos + blockSize > record.size) break
        val tag = r.i16()
        val optOffset = r.i32()
        if (tag == 0) {
            r.sizedInt() // attr
            r.sizedInt() // mask
            objFormatVersion = r.i32()
        }
        if (tag == 7 && optOffset > 0) {
            val m = LeReader(record, pos + 10)
            m.sizedInt() // shapeAttr
            val mask = m.sizedInt().value.toInt()
            if (mask and 0x1 != 0) {
                val o = LeReader(record, pos + optOffset)
                val len = o.i32()
                if (len in 0..o.remaining) textCommon = parseTextCommon(o.bytes(len), objFormatVersion)
            }
        }
        pos += blockSize
    }
    return textCommon
}

/** Parses the text-common block (objects spec §6). */
internal fun parseTextCommon(bytes: ByteArray, objFormatVersion: Int): TextCommon? {
    return try {
        val r = LeReader(bytes)
        val nUnits = r.i32()
        val text = if (nUnits <= 0) "" else String(r.bytes(nUnits * 2), Charsets.UTF_16LE)

        val nSpans = r.count(r.i32(), 2)
        val spans = ArrayList<TextCommon.SpanRecord>(nSpans)
        repeat(nSpans) {
            val body = r.bytes(r.u16())
            val b = LeReader(body)
            spans += TextCommon.SpanRecord(b.i32(), b.i32(), b.i32(), b.i32(), body)
        }

        val nParas = r.count(r.i32(), 2)
        val paragraphs = ArrayList<TextCommon.ParagraphRecord>(nParas)
        repeat(nParas) {
            val body = r.bytes(r.u16())
            val b = LeReader(body)
            paragraphs += TextCommon.ParagraphRecord(b.i32(), b.i32(), b.i32(), body)
        }

        val marginLeft = r.f32()
        val marginTop = r.f32()
        val marginRight = r.f32()
        val marginBottom = r.f32()
        val gravity = r.u8()
        val nSections = r.u16()
        val sections = ArrayList<TextCommon.SectionRange>(nSections)
        repeat(nSections) { sections += TextCommon.SectionRange(r.i32(), r.i32()) }

        var tailFlags: Int? = null
        var reservedTail: Int? = null
        val objectSpans = ArrayList<TextCommon.ObjectSpan>()
        if (objFormatVersion >= 2035) {
            tailFlags = r.i32()
            reservedTail = r.i32()
            if (tailFlags and TextCommon.TAIL_FLAG_OBJECT_SPANS != 0) {
                val n = r.i32()
                repeat(maxOf(n, 0)) {
                    r.i32() // entryLen
                    val objLen = r.i32()
                    val objType = r.i32()
                    val rec = r.bytes(objLen)
                    val charPos = r.i32()
                    objectSpans += TextCommon.ObjectSpan(objType, rec, charPos)
                }
            }
        }

        TextCommon(
            text = text,
            spans = spans,
            paragraphs = paragraphs,
            marginLeft = marginLeft,
            marginTop = marginTop,
            marginRight = marginRight,
            marginBottom = marginBottom,
            gravity = gravity,
            sections = sections,
            tailFlags = tailFlags,
            reservedTail = reservedTail,
            objectSpans = objectSpans,
        )
    } catch (_: Exception) {
        null
    }
}

/** Parses the `note.note` property block (core spec §3.4). */
internal fun parseNotePropertyBlock(raw: ByteArray, mask: Int): NotePropertyBlock {
    val r = LeReader(raw)
    var appName: String? = null
    var appVersion: Triple<Int, Int, String>? = null
    var author: List<String>? = null
    var authorInt: Int? = null
    var lat: Double? = null
    var lon: Double? = null
    var templateUri: String? = null
    var lastEditedPageIndex: Int? = null
    var lastEditedPageImageId: Int? = null
    var lastEditedPageTime: Long? = null
    var stringIdTable: List<NotePropertyBlock.IdText>? = null
    var bodyFontDelta: Int? = null
    var legacyPen: PenInfo? = null
    var voiceData: List<VoiceDataRecord>? = null
    var attachedFiles: List<NotePropertyBlock.AttachedFile>? = null
    var pen: PenInfo? = null

    if (mask and 0x1 != 0) appName = r.str16()
    if (mask and 0x2 != 0) {
        val major = r.i32(); val minor = r.i32(); val patch = r.str16() ?: ""
        appVersion = Triple(major, minor, patch)
    }
    if (mask and 0x4 != 0) {
        val a = r.str16() ?: ""; val b = r.str16() ?: ""; val c = r.str16() ?: ""
        author = listOf(a, b, c); authorInt = r.i32()
    }
    if (mask and 0x8 != 0) { lat = r.f64(); lon = r.f64() }
    if (mask and 0x40 != 0) templateUri = r.str16()
    if (mask and 0x80 != 0) lastEditedPageIndex = r.i32()
    if (mask and 0x200 != 0) { lastEditedPageImageId = r.i32(); lastEditedPageTime = r.i64() }
    if (mask and 0x400 != 0) {
        val byteLen = r.i32()
        if (byteLen > 0) {
            val count = r.u16()
            val list = ArrayList<NotePropertyBlock.IdText>(count)
            repeat(count) { list += NotePropertyBlock.IdText(r.i32(), r.str16() ?: "") }
            stringIdTable = list
        } else {
            stringIdTable = emptyList()
        }
    }
    if (mask and 0x800 != 0) bodyFontDelta = r.i32()
    if (mask and 0x1000 != 0) legacyPen = parsePenInfo(r, newLayout = false, blockLen = null)
    val voiceStart = r.pos
    if (mask and 0x2000 != 0) {
        val count = r.count(r.i32(), 4)
        val list = ArrayList<VoiceDataRecord>(count)
        repeat(count) { list += parseVoiceData(r) }
        voiceData = list
    }
    val voiceEnd = r.pos
    if (mask and 0x4000 != 0) {
        val count = r.u16()
        val list = ArrayList<NotePropertyBlock.AttachedFile>(count)
        repeat(count) { list += NotePropertyBlock.AttachedFile(r.str16() ?: "", r.i32()) }
        attachedFiles = list
    }
    if (mask and 0x8000 != 0) {
        val start = r.pos
        val blockLen = r.i32()
        pen = parsePenInfo(r, newLayout = true, blockLen = blockLen)
        r.seek(start + blockLen)
    }

    return NotePropertyBlock(
        mask = mask,
        appName = appName,
        appVersion = appVersion,
        author = author,
        authorInt = authorInt,
        latitude = lat,
        longitude = lon,
        templateUri = templateUri,
        lastEditedPageIndex = lastEditedPageIndex,
        lastEditedPageImageId = lastEditedPageImageId,
        lastEditedPageTime = lastEditedPageTime,
        stringIdTable = stringIdTable,
        bodyTextFontSizeDelta = bodyFontDelta,
        legacyPen = legacyPen,
        voiceData = voiceData,
        attachedFiles = attachedFiles,
        pen = pen,
        raw = raw,
        voiceStart = voiceStart,
        voiceEnd = voiceEnd,
    )
}

private fun parsePenInfo(r: LeReader, newLayout: Boolean, blockLen: Int?): PenInfo {
    val penName = r.str16() ?: ""
    val size = r.f32()
    val color = argb(r)
    val curvable = r.i32()
    val advanced = r.str16() ?: ""
    val eraser = r.i32()
    val sizeLevel = r.i32()
    val particleDensity = r.i32()
    var particleSize: Float? = null
    var fixedWidth: Int? = null
    if (newLayout) {
        particleSize = r.f32()
        fixedWidth = r.i32()
    }
    val hsv = listOf(r.f32(), r.f32(), r.f32())
    val colorUiInfo = r.i32()
    return PenInfo(penName, size, color, curvable, advanced, eraser, sizeLevel, particleDensity, particleSize, fixedWidth, hsv, colorUiInfo)
}

private fun parseVoiceData(r: LeReader): VoiceDataRecord {
    val recLen = r.i32()
    val end = r.pos + recLen
    val fileId = r.i32()
    val name = r.str16() ?: ""
    val playTime = r.str16() ?: ""
    val createdTime = r.i64()
    val nEvents = r.count(r.i32(), 12)
    val events = ArrayList<VoiceDataRecord.Event>(nEvents)
    repeat(nEvents) { events += VoiceDataRecord.Event(r.i32(), r.i64()) }
    val recordingTime = if (r.pos + 8 <= end) r.i64() else null
    val extra = if (r.pos < end) r.bytes(end - r.pos) else ByteArray(0)
    r.seek(end)
    return VoiceDataRecord(fileId, name, playTime, createdTime, events, recordingTime, extra)
}

/** Parses the page property block (core spec §5.3). */
internal fun parsePagePropertyBlock(raw: ByteArray, mask: Int, formatVersion: Int): PagePropertyBlock {
    val r = LeReader(raw)
    var drawnRect: RectF64? = null
    var tags: List<String>? = null
    var templateUri: String? = null
    var bgImageId: Int? = null
    var bgImageMode: Int? = null
    var bgColor: Long? = null
    var bgWidth: Int? = null
    var bgRotation: Int? = null
    var pdf: List<PdfPlacement>? = null
    var templateType: Int? = null
    var canvasCacheRaw: ByteArray? = null
    var importedDataHeight: Int? = null
    var unknown12: ByteArray? = null

    if (mask and 0x1 != 0) drawnRect = RectF64(r.f64(), r.f64(), r.f64(), r.f64())
    if (mask and 0x2 != 0) {
        val n = r.u16()
        tags = List(n) { r.str16() ?: "" }
    }
    if (mask and 0x4 != 0) templateUri = r.str16()
    if (mask and 0x8 != 0) bgImageId = r.i32()
    if (mask and 0x10 != 0) bgImageMode = r.i32()
    if (mask and 0x20 != 0) bgColor = argb(r)
    if (mask and 0x40 != 0) bgWidth = r.i32()
    if (mask and 0x80 != 0) bgRotation = r.i32()
    if (mask and 0x100 != 0) {
        val usesF32 = formatVersion < 2034
        val n = r.u16()
        val list = ArrayList<PdfPlacement>(n)
        repeat(n) {
            val fileId = r.i32()
            val pageIndex = r.i32()
            list += PdfPlacement(fileId, pageIndex, r.bytes(16), usesF32)
        }
        pdf = list
    }
    if (mask and 0x200 != 0) templateType = r.i32()
    if (mask and 0x400 != 0) {
        val start = r.pos
        val n = r.i32()
        val entrySize = r.u16()
        r.bytes(n * entrySize)
        canvasCacheRaw = raw.copyOfRange(start, r.pos)
    }
    if (mask and 0x800 != 0) importedDataHeight = r.i32()
    if (mask and 0x1000 != 0) unknown12 = r.bytes(4)

    return PagePropertyBlock(
        mask = mask,
        drawnRect = drawnRect,
        tags = tags,
        templateUri = templateUri,
        bgImageId = bgImageId,
        bgImageMode = bgImageMode,
        bgColor = bgColor,
        bgWidth = bgWidth,
        bgRotation = bgRotation,
        pdf = pdf,
        templateType = templateType,
        canvasCacheRaw = canvasCacheRaw,
        importedDataHeight = importedDataHeight,
        unknown12 = unknown12,
        raw = raw,
    )
}

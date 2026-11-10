package org.samesame.notes.core.sdocx

import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.sdocx.io.LeReader
import org.samesame.notes.core.sdocx.io.LeWriter
import org.samesame.notes.core.sdocx.map.SdocxMapper
import org.samesame.notes.core.sdocx.record.NoteRecord

/**
 * Handwriting of a note created in SameSame, written as real type-1 stroke records: each stroke's
 * points, colour and width, and its pen through the note's string table (pen class name and
 * advanced-setting string, from the stroke's `PenBrushes` key via [SdocxPens]). The pen of the last
 * stroke is also stored as the note's "last used pen", as the reference app does.
 */
object SdocxNativeInk {

    /** A complete archive for [document] with [pageInk] (SameSame page id → strokes in z-order). */
    fun export(
        document: NoteDocument,
        pageInk: Map<String, List<SdocxInkSample>>,
        nowUs: Long,
        lastViewedPageIndex: Int = 0,
        inlineImages: Map<String, SdocxImageSource> = emptyMap(),
        uuid: () -> String = { java.util.UUID.randomUUID().toString() },
    ): SdocxArchive = apply(SdocxDocumentBuilder.toArchive(document, nowUs, lastViewedPageIndex, inlineImages, uuid), pageInk, nowUs, uuid)

    /** Adds [pageInk] to a freshly built [archive]. */
    fun apply(
        archive: SdocxArchive,
        pageInk: Map<String, List<SdocxInkSample>>,
        nowUs: Long,
        uuid: () -> String = { java.util.UUID.randomUUID().toString() },
    ): SdocxArchive {
        val scale = SdocxMapper.geometryScale(archive)
        val strokes = pageInk
            .mapKeys { SdocxDocumentBuilder.pageId(it.key) }
            .mapValues { SdocxInk.newStrokes(it.value, scale) }
            .filterValues { it.isNotEmpty() }
        if (strokes.isEmpty()) return archive
        val inked = SdocxStrokeMerge.apply(archive, strokes, nowUs, uuid, countAsEdit = false)
        val last = archive.pages.asReversed().firstNotNullOfOrNull { strokes[it.pageId]?.lastOrNull() } ?: return inked
        val note = withLastUsedPen(inked.note, last) ?: return inked
        return inked.rebuilt(note = note)
    }

    /**
     * [note] with the new-layout pen info (core spec §3.4 bit 15) describing [stroke]. Name, size and
     * colour are the stroke's; the other fields are the values every device fixture carries (curve
     * on, no advanced setting, particle density 1) with the default size level, and the colour again
     * as hue (degrees), saturation and value. Null when the note already has pen or later properties
     * this writer does not place.
     */
    private fun withLastUsedPen(note: NoteRecord, stroke: SdocxPageStroke): NoteRecord? {
        val mask = note.propMask
        if (mask and 0xF07F != 0 || stroke.pen == null) return null
        val raw = note.properties.raw
        val r = LeReader(raw)
        if (mask and 0x80 != 0) r.i32()
        if (mask and 0x200 != 0) r.bytes(12)
        if (mask and 0x400 != 0) { val n = r.i32(); if (n > 0) r.bytes(n) }
        if (mask and 0x800 != 0) r.i32()
        val at = r.pos
        val colour = stroke.stroke.colour
        val body = LeWriter().apply {
            str16(stroke.pen.className)
            f32(stroke.stroke.penSize)
            i32(colour.toInt())
            i32(1) // curvable
            str16("") // advanced setting
            i32(0) // eraser enabled
            i32(DEFAULT_SIZE_LEVEL)
            i32(1) // particle density
            f32(0f) // particle size
            i32(0) // fixed width
            hsv(colour).forEach { f32(it) }
            i32(0) // colour UI info
        }.toByteArray()
        val out = LeWriter(raw.size + body.size + 4)
        out.bytes(raw.copyOfRange(0, at))
        out.i32(body.size + 4) // the length counts itself
        out.bytes(body)
        out.bytes(raw.copyOfRange(at, raw.size))
        val newMask = mask or 0x8000
        return NoteRecord(
            propOffset = note.propOffset,
            noteFlags = note.noteFlags,
            propMask = newMask,
            formatVersion = note.formatVersion,
            noteId = note.noteId,
            fileRevision = note.fileRevision,
            createdTime = note.createdTime,
            modifiedTime = note.modifiedTime,
            width = note.width,
            height = note.height,
            pageHorizontalPadding = note.pageHorizontalPadding,
            pageVerticalPadding = note.pageVerticalPadding,
            minFormatVersion = note.minFormatVersion,
            title = note.title,
            body = note.body,
            properties = parseNotePropertyBlock(out.toByteArray(), newMask),
            docHash = note.docHash,
        )
    }

    /** Hue in degrees, saturation and value of an ARGB colour's RGB part. */
    internal fun hsv(argb: Long): List<Float> {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return listOf(hue, if (max == 0f) 0f else delta / max, max)
    }

    /** The pen panel's default size step, stored with the last used pen. */
    private const val DEFAULT_SIZE_LEVEL = 30
}

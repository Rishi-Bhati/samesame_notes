package org.samesame.notes.core.sdocx

import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.sdocx.io.LeWriter
import org.samesame.notes.core.sdocx.io.SizedInt
import org.samesame.notes.core.sdocx.record.EndTagRecord
import org.samesame.notes.core.sdocx.record.LayerRecord
import org.samesame.notes.core.sdocx.record.MediaRegistry
import org.samesame.notes.core.sdocx.record.NoteRecord
import org.samesame.notes.core.sdocx.record.ObjectEntry
import org.samesame.notes.core.sdocx.record.PageIdInfo
import org.samesame.notes.core.sdocx.record.PageRecord
import org.samesame.notes.core.sdocx.record.TextBoxRecord
import kotlin.math.roundToInt

/**
 * Builds a from-scratch [SdocxArchive] from a native [NoteDocument].
 *
 * The result has the shape of a note written by the reference app (compared field by field with the
 * device fixtures): format version 4000 everywhere, a 1080 unit wide page, an empty note id and app
 * fields, list page mode, one layer per page, the extended media registry, and an end tag with the
 * full current tail. Title, body text and page backgrounds are written here; handwriting is added
 * by [SdocxNativeInk], page content with no object mapping by [SdocxPageImageFallback].
 *
 * The returned archive is byte-stable: the same document, [nowUs] and uuid source always produce
 * the same bytes.
 */
object SdocxDocumentBuilder {

    /** Page width of a new note. It equals SameSame's page width, so ink coordinates are written 1:1. */
    const val PAGE_WIDTH_PX = 1080

    fun toArchive(
        document: NoteDocument,
        nowUs: Long,
        /** The note's stored reading position (it is no longer part of [document]). */
        lastViewedPageIndex: Int = 0,
        uuid: () -> String = { java.util.UUID.randomUUID().toString() },
    ): SdocxArchive {
        val pageSpecs = document.pages.ifEmpty { listOf(PageSpec(id = uuid())) }
        val pageHeight = pageHeightPx(document)
        // Total height as in real files: the pages plus the padding between them.
        val noteHeight = pageHeight * pageSpecs.size + PAGE_VERTICAL_PADDING * (pageSpecs.size - 1)

        val titleBox = SdocxTextCodec.textBox(
            uuid = uuid(), modifiedUs = nowUs, text = RichText.of(document.title), titled = true,
        )
        val bodyBox = SdocxTextCodec.textBox(
            uuid = uuid(), modifiedUs = nowUs, text = document.body, titled = false,
        )

        val notePropsRaw = LeWriter().apply {
            i32(0) // bit 7: lastEditedPageIndex
            i32(-1) // bit 9: lastEditedPageImageId
            i64(0) //        lastEditedPageTime
            i32(0) // bit 11: bodyTextFontSizeDelta
            i32(FIXED_TEXT_DIRECTION) // bit 18
            i32(FIXED_BACKGROUND_THEME) // bit 19
        }.toByteArray()

        val note = NoteRecord(
            propOffset = 0,
            noteFlags = SizedInt(4, 8, byteArrayOf(8, 0, 0, 0)),
            propMask = NOTE_PROP_MASK,
            formatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
            // Real files leave the id empty; the app assigns its own when it stores the note.
            noteId = "",
            fileRevision = 1,
            createdTime = nowUs,
            modifiedTime = nowUs,
            width = PAGE_WIDTH_PX,
            height = noteHeight,
            pageHorizontalPadding = 0,
            pageVerticalPadding = PAGE_VERTICAL_PADDING,
            minFormatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
            title = TextBoxRecord(titleBox, parseTextCommonFromTextBox(titleBox)),
            body = TextBoxRecord(bodyBox, parseTextCommonFromTextBox(bodyBox)),
            properties = parseNotePropertyBlock(notePropsRaw, NOTE_PROP_MASK),
            docHash = ByteArray(32),
        )

        val landscape = document.layout.ratio.heightOverWidth < 1f
        val pages = pageSpecs.map { spec ->
            val bgColor = spec.backgroundColor ?: document.style.backgroundColor ?: DEFAULT_BG
            val propsRaw = LeWriter().apply {
                i32(2) // bgImageMode
                i32(bgColor.toInt()) // bgColor
                i32(PAGE_WIDTH_PX) // bgWidth
            }.toByteArray()
            PageRecord(
                orientation = if (landscape) 1 else 0,
                width = PAGE_WIDTH_PX,
                height = pageHeight,
                offsetX = 0,
                offsetY = 0,
                pageId = pageId(spec.id),
                modifiedTime = nowUs,
                formatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
                minFormatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
                pageFlags = SizedInt(4, 0, byteArrayOf(0, 0, 0, 0)),
                propMask = PAGE_PROP_MASK,
                properties = parsePagePropertyBlock(propsRaw, PAGE_PROP_MASK, SdocxFormat.WRITE_FORMAT_VERSION),
                // Every page of a real file has one layer, even an empty page.
                layers = listOf(newLayer(uuid(), nowUs, emptyList())),
                originalHash = ByteArray(32),
                currentLayerIndex = 0,
            )
        }

        val endTag = EndTagRecord(
            formatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
            noteId = "",
            modifiedTime = nowUs,
            propertyFlags = if (landscape) 0x2 else 0,
            coverImage = "",
            noteWidth = PAGE_WIDTH_PX,
            noteHeight = noteHeight.toFloat(),
            appName = "",
            appMajorVersion = -1,
            appMinorVersion = -1,
            appPatchName = "",
            minFormatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
            createdTime = nowUs,
            lastViewedPageIndex = lastViewedPageIndex.coerceIn(0, pages.size - 1),
            pageMode = SdocxFormat.PageMode.LIST,
            documentType = SdocxFormat.DocumentType.UNLOCKED,
            ownerId = "",
            reserved1 = ByteArray(0),
            reserved2 = ByteArray(0),
            displayCreatedTime = nowUs,
            displayModifiedTime = nowUs,
            lastRecognizedDataModifiedTime = 0,
            fixedFont = "",
            fixedTextDirection = FIXED_TEXT_DIRECTION,
            fixedBackgroundTheme = FIXED_BACKGROUND_THEME,
            hasTail = true,
            hasFixedFontGroup = true,
            // Eight 0xFF bytes close the end tag of every version-4000 file (core spec §2).
            tailExtra = ByteArray(8) { 0xFF.toByte() },
        )

        return SdocxArchive(
            endTag = endTag,
            note = note,
            pageIdInfo = PageIdInfo(ByteArray(32), pages.map { PageIdInfo.Entry(it.pageId, ByteArray(32)) }),
            pages = pages,
            media = MediaRegistry(extended = true, formatVersion = SdocxFormat.WRITE_FORMAT_VERSION, entries = emptyList(), hasTerminator = true),
            mediaFiles = emptyMap(),
            extraEntries = emptyMap(),
        )
    }

    /** A layer as the reference app creates it: visible, event-forwardable, with an id and a modified time. */
    internal fun newLayer(layerId: String, nowUs: Long, objects: List<ObjectEntry>): LayerRecord = LayerRecord(
        flags = 0x02,
        optMask = 0x18,
        layerNumber = 0,
        optionalRaw = LeWriter().apply {
            str16(layerId)
            i64(nowUs)
        }.toByteArray(),
        objects = objects,
        originalHash = ByteArray(32),
        layerId = layerId,
        modifiedTime = nowUs,
    )

    /**
     * The `.sdocx` page id for a SameSame page id. Page ids are 36-character uuid texts (they are the
     * page file names and are read with a fixed-size uuid field); a SameSame id of another form is
     * replaced by a name-based uuid derived from it, so the same page always gets the same id.
     */
    fun pageId(id: String): String {
        val canonical = runCatching { java.util.UUID.fromString(id).toString() }.getOrNull()
        if (canonical != null && canonical.length == SdocxFormat.PAGE_ID_LENGTH && canonical == id.lowercase()) return canonical
        return java.util.UUID.nameUUIDFromBytes("samesame-page:$id".toByteArray(Charsets.UTF_8)).toString()
    }

    /** Page height for the note's ratio; 1527 for A4 portrait at width 1080, as in real files. */
    private fun pageHeightPx(document: NoteDocument): Int =
        (PAGE_WIDTH_PX * document.layout.ratio.heightOverWidth).roundToInt().coerceAtLeast(1)

    /** Bits 7, 9, 11, 18, 19: what a real note without handwriting carries. */
    private const val NOTE_PROP_MASK = 0xC0A80
    private const val PAGE_PROP_MASK = 0x70
    private const val PAGE_VERTICAL_PADDING = 24
    private const val DEFAULT_BG = 0xFFFCFCFCL
    private const val FIXED_TEXT_DIRECTION = 2
    private const val FIXED_BACKGROUND_THEME = 2
}

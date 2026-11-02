package org.samesame.notes.core.sdocx

import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.sdocx.io.LeWriter
import org.samesame.notes.core.sdocx.record.EndTagRecord
import org.samesame.notes.core.sdocx.record.MediaRegistry
import org.samesame.notes.core.sdocx.record.NoteRecord
import org.samesame.notes.core.sdocx.record.PageIdInfo
import org.samesame.notes.core.sdocx.record.PageRecord
import org.samesame.notes.core.sdocx.record.TextBoxRecord
import kotlin.math.roundToInt

/**
 * Builds a from-scratch [SdocxArchive] from a native [NoteDocument] (spec §13). Only words, title,
 * body text and page backgrounds are represented; ink and floating objects are not (the stroke
 * payload layout is unknown, spec §13.3 item 4), so callers rasterise pages and feed the resulting
 * PNGs to [SdocxPageImageFallback] before writing. Media referenced only by page images is
 * registered by the fallback.
 *
 * The returned archive is byte-stable: its records carry no timestamps read from the source, so the
 * same document + [nowUs] always produces the same bytes.
 */
object SdocxDocumentBuilder {

    /** The note-level defaults from spec §13.2. */
    const val PAGE_WIDTH_PX = 2000

    fun toArchive(
        document: NoteDocument,
        nowUs: Long,
        /** The note's stored reading position (it is no longer part of [document]). */
        lastViewedPageIndex: Int = 0,
        uuid: () -> String = { java.util.UUID.randomUUID().toString() },
    ): SdocxArchive {
        val pageSpecs = document.pages.ifEmpty { listOf(PageSpec(id = uuid())) }
        val pageHeight = pageHeightPx(document)
        val noteHeight = pageHeight * pageSpecs.size + PAGE_VERTICAL_PADDING

        val titleBox = SdocxTextCodec.textBox(
            uuid = uuid(), modifiedUs = nowUs, text = RichText.of(document.title), titled = true,
        )
        val bodyBox = SdocxTextCodec.textBox(
            uuid = uuid(), modifiedUs = nowUs, text = document.body, titled = false,
        )

        val notePropsRaw = LeWriter().apply {
            i32(0) // lastEditedPageIndex
            i32(-1) // lastEditedPageImageId
            i64(0) // lastEditedPageTime
            i32(0) // bodyTextFontSizeDelta
        }.toByteArray()

        val note = NoteRecord(
            propOffset = 0,
            noteFlags = org.samesame.notes.core.sdocx.io.SizedInt(4, 8, byteArrayOf(8, 0, 0, 0)),
            propMask = NOTE_PROP_MASK,
            formatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
            noteId = document.id,
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


        val pages = pageSpecs.map { spec ->
            val bgColor = spec.backgroundColor ?: document.style.backgroundColor ?: DEFAULT_BG
            val propsRaw = LeWriter().apply {
                i32(2) // bgImageMode
                i32(bgColor.toInt()) // bgColor
                i32(PAGE_WIDTH_PX) // bgWidth
            }.toByteArray()
            PageRecord(
                orientation = if (document.layout.ratio.heightOverWidth < 1f) 1 else 0,
                width = PAGE_WIDTH_PX,
                height = pageHeight,
                offsetX = 0,
                offsetY = 0,
                pageId = spec.id,
                modifiedTime = nowUs,
                formatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
                minFormatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
                pageFlags = org.samesame.notes.core.sdocx.io.SizedInt(4, 0, byteArrayOf(0, 0, 0, 0)),
                propMask = PAGE_PROP_MASK,
                properties = parsePagePropertyBlock(propsRaw, PAGE_PROP_MASK, SdocxFormat.WRITE_FORMAT_VERSION),
                layers = emptyList(),
                originalHash = ByteArray(32),
                currentLayerIndex = 0,
            )
        }

        val endTag = EndTagRecord(
            formatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
            noteId = document.id,
            modifiedTime = nowUs,
            propertyFlags = if (document.layout.ratio.heightOverWidth < 1f) 0x2 else 0,
            coverImage = "",
            noteWidth = PAGE_WIDTH_PX,
            noteHeight = noteHeight.toFloat(),
            appName = APP_NAME,
            appMajorVersion = 0,
            appMinorVersion = 1,
            appPatchName = "",
            minFormatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
            createdTime = nowUs,
            lastViewedPageIndex = lastViewedPageIndex,
            pageMode = if (document.layout.mode == PageMode.Pages) SdocxFormat.PageMode.SINGLE else SdocxFormat.PageMode.LIST,
            documentType = SdocxFormat.DocumentType.UNLOCKED,
            ownerId = "",
            reserved1 = ByteArray(0),
            reserved2 = ByteArray(0),
            displayCreatedTime = nowUs,
            displayModifiedTime = nowUs,
            lastRecognizedDataModifiedTime = 0,
            fixedFont = "",
            fixedTextDirection = 2,
            fixedBackgroundTheme = 2,
            hasTail = true,
        )

        return SdocxArchive(
            endTag = endTag,
            note = note,
            pageIdInfo = PageIdInfo(ByteArray(32), pages.map { PageIdInfo.Entry(it.pageId, ByteArray(32)) }),
            pages = pages,
            media = MediaRegistry(false, SdocxFormat.WRITE_FORMAT_VERSION, emptyList(), false),
            mediaFiles = emptyMap(),
            extraEntries = emptyMap(),
        )
    }

    /** A4 portrait height (2000 x 2828) for the note-wide default; per-page ratio follows the document. */
    private fun pageHeightPx(document: NoteDocument): Int =
        (PAGE_WIDTH_PX * document.layout.ratio.heightOverWidth).roundToInt().coerceAtLeast(1)

    private const val NOTE_PROP_MASK = 0x0A80
    private const val PAGE_PROP_MASK = 0x70
    private const val PAGE_VERTICAL_PADDING = 44
    private const val DEFAULT_BG = 0xFFFCFCFCL
    private const val APP_NAME = "SameSame notes"
}

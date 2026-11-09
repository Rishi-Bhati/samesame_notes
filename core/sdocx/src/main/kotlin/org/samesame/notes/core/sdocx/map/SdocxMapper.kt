package org.samesame.notes.core.sdocx.map

import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.ScrollDirection
import org.samesame.notes.core.model.document.TemplateRef
import org.samesame.notes.core.model.document.ViewState
import org.samesame.notes.core.model.document.NoteStyle
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteRecording
import org.samesame.notes.core.model.document.VoiceObject
import org.samesame.notes.core.model.document.VoiceTimelineEvent
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextAlign
import org.samesame.notes.core.model.richtext.TextRun
import org.samesame.notes.core.sdocx.SdocxArchive
import org.samesame.notes.core.sdocx.SdocxLock
import org.samesame.notes.core.sdocx.io.LeReader
import org.samesame.notes.core.sdocx.record.PageRecord
import org.samesame.notes.core.sdocx.record.TextCommon

/**
 * Maps a parsed `.sdocx` [SdocxArchive] to `org.samesame.notes.core.model` types (spec §14).
 *
 * Object records (strokes, images, shapes, …) stay opaque inside the archive; only the note title,
 * body rich text, page list, layout, style and note-level voice recordings are mapped here. Geometry is scaled from the sdocx
 * page width to SameSame page units (`Limits.PAGE_WIDTH_PU`).
 */
object SdocxMapper {

    fun lockOf(archive: SdocxArchive): SdocxLock =
        SdocxLock(archive.endTag.isLocked, archive.endTag.ownerId)

    fun title(archive: SdocxArchive): String = archive.note.title.text?.text ?: ""

    /** Everything the repository needs to turn a parsed archive into a new note bundle. */
    class ImportBundle(
        val document: NoteDocument,
        val title: String,
        val lock: SdocxLock,
        /** The archive's reading position; v7 keeps it in the per-note store, not in the document. */
        val viewState: ViewState?,
        /** Original entry name (`media/<name>`) → bytes. */
        val media: Map<String, ByteArray>,
        /** ZIP entries outside the modelled set, preserved verbatim. */
        val extraEntries: Map<String, ByteArray>,
    )

    fun toImportBundle(archive: SdocxArchive, noteId: String): ImportBundle = ImportBundle(
        document = toNoteDocument(archive, noteId),
        title = title(archive),
        lock = lockOf(archive),
        viewState = viewStateOf(archive),
        media = archive.mediaFiles,
        extraEntries = archive.extraEntries,
    )

    /** The `lastViewedPageIndex` sdocx stores, as a reading position (offset and zoom are not in the format). */
    fun viewStateOf(archive: SdocxArchive): ViewState? = archive.endTag.lastViewedPageIndex
        .takeIf { it in 0 until archive.pages.size }
        ?.let { ViewState(it, 0f, 1f) }

    /**
     * Rewrites `TemplateRef.Image("media/<original name>")` to the stored bundle path returned for
     * that media (content-addressed `media/<sha256>.<ext>`), keeping the document displayable.
     */
    fun withMediaPaths(document: NoteDocument, storedPaths: Map<String, String>): NoteDocument {
        if (storedPaths.isEmpty()) return document
        val pages = document.pages.map { spec ->
            val template = spec.template
            if (template is TemplateRef.Image) {
                spec.copy(template = TemplateRef.Image(storedPaths[template.media] ?: template.media))
            } else {
                spec
            }
        }
        val inline = document.inlineObjects.map { obj ->
            when (obj) {
                is ImageObject -> obj.copy(media = storedPaths[obj.media] ?: obj.media)
                is VoiceObject -> obj.copy(media = storedPaths[obj.media] ?: obj.media)
                else -> obj
            }
        }
        val recordings = document.recordings.map { it.copy(media = storedPaths[it.media] ?: it.media) }
        return document.copy(pages = pages, inlineObjects = inline, recordings = recordings)
    }

    /**
     * Overlays the edited title/body of [document] onto an imported [archive] (spec §12.2/§12.3).
     * Returns the archive unchanged when neither text changed, so an unedited round trip stays
     * byte-stable; otherwise only the two note text-box records and the note/end-tag stamps change,
     * while every page, layer, object and media unit is reused verbatim.
     */
    fun applyDocumentEdits(
        archive: SdocxArchive,
        document: NoteDocument,
        nowUs: Long,
        /** The note's stored page; null keeps the archive's own value (a scroll is not a document edit). */
        lastViewedPageIndex: Int? = null,
        newUuid: () -> String = { java.util.UUID.randomUUID().toString() },
    ): SdocxArchive {
        val titleChanged = title(archive) != document.title
        val bodyChanged = archive.note.body.text?.text.orEmpty() != document.body.text
        val pageIndex = lastViewedPageIndex?.coerceIn(0, maxOf(archive.pages.size - 1, 0))
        val viewChanged = pageIndex != null && pageIndex != archive.endTag.lastViewedPageIndex
        if (!titleChanged && !bodyChanged) {
            // A reading position is stored in end_tag.bin, so an export must update it even when the
            // note text did not change. It is not an edit: note modified time and docHash stay.
            if (!viewChanged) return archive
            return archive.withEndTag(
                org.samesame.notes.core.sdocx.SdocxMirror.endTag(
                    archive.note, archive.endTag,
                    modifiedTimeUs = archive.note.modifiedTime,
                    displayModifiedTimeUs = archive.endTag.displayModifiedTime,
                    lastViewedPageIndex = pageIndex,
                ),
            )
        }

        // Rebuild only the changed text box. The previous record lets the codec carry spans and
        // paragraphs it cannot model (font names, hyperlinks, timestamps, line spacing) across the
        // edit by re-anchoring them on unchanged text (spec §12.2, §14.3).
        val titleBox = org.samesame.notes.core.sdocx.SdocxTextCodec.textBox(
            uuid = newUuid(), modifiedUs = nowUs, text = RichText.of(document.title), titled = true,
            previous = archive.note.title,
        )
        val bodyBox = org.samesame.notes.core.sdocx.SdocxTextCodec.textBox(
            uuid = newUuid(), modifiedUs = nowUs, text = document.body, titled = false,
            previous = archive.note.body,
        )
        val note = org.samesame.notes.core.sdocx.record.NoteRecord(
            propOffset = archive.note.propOffset,
            noteFlags = archive.note.noteFlags,
            propMask = archive.note.propMask,
            formatVersion = archive.note.formatVersion,
            // The sdocx noteId identifies the source note; a SameSame note id is not a replacement.
            noteId = archive.note.noteId,
            fileRevision = archive.note.fileRevision + 1,
            createdTime = archive.note.createdTime,
            modifiedTime = nowUs,
            width = archive.note.width,
            height = archive.note.height,
            pageHorizontalPadding = archive.note.pageHorizontalPadding,
            pageVerticalPadding = archive.note.pageVerticalPadding,
            minFormatVersion = archive.note.minFormatVersion,
            title = if (!titleChanged) archive.note.title else org.samesame.notes.core.sdocx.record.TextBoxRecord(
                titleBox, org.samesame.notes.core.sdocx.parseTextCommonFromTextBox(titleBox),
            ),
            body = if (!bodyChanged) archive.note.body else org.samesame.notes.core.sdocx.record.TextBoxRecord(
                bodyBox, org.samesame.notes.core.sdocx.parseTextCommonFromTextBox(bodyBox),
            ),
            properties = archive.note.properties,
            docHash = archive.note.docHash,
        )
        val endTag = org.samesame.notes.core.sdocx.SdocxMirror.endTag(
            note, archive.endTag, modifiedTimeUs = nowUs, displayModifiedTimeUs = nowUs,
            // Keep the imported note's sdocx page mode: list and single both map to SameSame Pages on
            // import (see toNoteDocument), so deriving it from the layout would turn a LIST note into
            // SINGLE on the first text edit. SdocxMirror copies the source pageMode.
            lastViewedPageIndex = (pageIndex ?: archive.endTag.lastViewedPageIndex),
        )
        return SdocxArchive(
            endTag = endTag,
            note = note,
            pageIdInfo = archive.pageIdInfo,
            pages = archive.pages,
            media = archive.media,
            mediaFiles = archive.mediaFiles,
            extraEntries = archive.extraEntries,
        )
    }

    fun geometryScale(archive: SdocxArchive): Float =
        Limits.PAGE_WIDTH_PU / archive.note.width.toFloat().coerceAtLeast(1f)

    fun toNoteDocument(archive: SdocxArchive, noteId: String): NoteDocument {
        val title = title(archive)
        val body = bodyRichText(archive.note.body.text)
        val pages = archive.pages.map { toPageSpec(it, archive) }
        // A .sdocx always stores an explicit page record list. Its `pageMode` (list / single) only
        // describes scrolling, not an endless canvas, so both modes map to fixed SameSame pages.
        // Mapping LIST to PageMode.Infinite made the composer's infinite-mode auto-grow append blank
        // pages on open (a 2-page fixture became 4 pages before the user did anything).
        val mode = PageMode.Pages
        val ratio = if (pages.isNotEmpty()) ratioOf(archive.pages.first()) else PageRatio.A4Portrait
        val backgroundColor = pages.firstOrNull()?.backgroundColor

        return NoteDocument(
            id = noteId,
            title = title,
            layout = PageLayout(mode = mode, ratio = ratio, scroll = ScrollDirection.Vertical),
            style = NoteStyle(
                backgroundColor = backgroundColor,
                adjustForDarkMode = archive.note.adjustForDarkMode,
            ),
            body = body,
            recordings = mapRecordings(archive),
            pages = pages,
        )
    }

    fun normalizeBackgroundColor(color: Long?): Long? {
        if (color == null || color == -1L || color == 0L) return null
        val rgb = color and 0xFFFFFFL
        // the reference default base_background_color_white is 0xFCFCFC; 0xFFFFFF is pure white.
        if (rgb == 0xFCFCFCL || rgb == 0xFFFFFFL) return null
        return color
    }

    /** Spec objects §7 uses display text for elapsed duration; recordingTime has no specified unit. */
    fun parsePlayTimeMs(playTime: String?): Long {
        if (playTime.isNullOrBlank()) return 0L
        val fields = playTime.trim().split(':')
        if (fields.size !in 1..3) return 0L
        val parts = fields.map { field ->
            if (field.isEmpty() || field.any { !it.isDigit() }) return 0L
            field.toLongOrNull() ?: return 0L
        }
        if (parts.drop(1).any { it >= 60L }) return 0L
        return runCatching {
            val seconds = parts.fold(0L) { total, part -> Math.addExact(Math.multiplyExact(total, 60L), part) }
            Math.multiplyExact(seconds, 1000L)
        }.getOrDefault(0L)
    }

    /**
     * Objects spec §7: note-level recordings live in the note's recording list (the reference
     *), not on a page — the pages of an imported note stay exactly as they are and
     * the recordings surface in the recording bar and its list instead. Original names, timestamps
     * and timeline events are mapped here; the raw records also remain intact in the side-channel.
     */
    fun mapRecordings(archive: SdocxArchive): List<NoteRecording> {
        val voiceData = archive.note.properties.voiceData ?: return emptyList()
        if (voiceData.isEmpty()) return emptyList()

        val recordings = mutableListOf<NoteRecording>()
        val mediaById = archive.mediaByBindNumber

        for ((index, item) in voiceData.withIndex()) {
            val fileName = mediaById[item.fileId]?.fileName
            val mediaPath = if (fileName != null) "media/$fileName" else continue
            // A registry may reference an attachment still on the server (§8.1); do not offer a
            // broken player for missing bytes. The original record still round-trips unchanged.
            if (archive.mediaFiles[mediaPath]?.isNotEmpty() != true) continue
            val identity = "${archive.note.noteId}:voice:$index:${item.fileId}"
            val recordingId = java.util.UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()
            recordings.add(
                NoteRecording(
                    id = recordingId,
                    media = mediaPath,
                    name = item.name,
                    createdAtMs = timestampToMs(item.createdTime),
                    // Unspecified recordingTime stays "unknown" (0): the display text is the fallback.
                    elapsedMs = item.recordingTime ?: 0L,
                    displayDuration = item.playTime,
                    sourceFileId = item.fileId,
                    timeline = item.events.map { VoiceTimelineEvent(it.action, timestampToMs(it.timestamp)) },
                ),
            )
        }
        return recordings
    }

    /** Spec §0.1 reader rule: time values above 10^14 are microseconds, smaller ones are milliseconds. */
    private fun timestampToMs(value: Long): Long =
        if (kotlin.math.abs(value) > 100_000_000_000_000L) value / 1000L else value

    fun toPageSpec(page: PageRecord, archive: SdocxArchive): PageSpec {
        val bgImageId = page.properties.bgImageId
        val template = if (bgImageId != null && bgImageId >= 0) {
            val name = archive.mediaByBindNumber[bgImageId]?.fileName
            if (name != null) TemplateRef.Image("media/$name") else TemplateRef.Builtin(PageTemplate.Blank)
        } else {
            TemplateRef.Builtin(PageTemplate.Blank)
        }
        return PageSpec(
            id = page.pageId,
            template = template,
            backgroundColor = normalizeBackgroundColor(page.properties.bgColor),
        )
    }

    // ---- rich text (spec §14.3) ----

    fun bodyRichText(tc: TextCommon?): RichText {
        if (tc == null || tc.text.isEmpty()) return RichText()
        val n = tc.text.length
        val styles = MutableList(n) { CharStyle() }

        for (span in tc.spans) {
            val s = span.start.coerceIn(0, n)
            val e = span.end.coerceIn(0, n)
            if (e <= s) continue
            val p = LeReader(span.raw, 16)
            val transform: ((CharStyle) -> CharStyle)? = when (span.kind) {
                1 -> run {
                    val value = p.i32().toLong() and 0xFFFFFFFFL
                    val t: (CharStyle) -> CharStyle = { style -> style.copy(color = value) }
                    t
                }
                17 -> run {
                    val value = p.i32().toLong() and 0xFFFFFFFFL
                    val t: (CharStyle) -> CharStyle = { style -> style.copy(highlight = value) }
                    t
                }
                3 -> run {
                    val value = p.f32()
                    val t: (CharStyle) -> CharStyle = { style -> style.copy(fontSize = value) }
                    t
                }
                5 -> run {
                    val value = p.u8() != 0
                    val t: (CharStyle) -> CharStyle = { style -> style.copy(bold = value) }
                    t
                }
                6 -> run {
                    val value = p.u8() != 0
                    val t: (CharStyle) -> CharStyle = { style -> style.copy(italic = value) }
                    t
                }
                7 -> run {
                    val value = p.u8() != 0
                    val t: (CharStyle) -> CharStyle = { style -> style.copy(underline = value) }
                    t
                }
                20 -> run {
                    val value = p.u8() != 0
                    val t: (CharStyle) -> CharStyle = { style -> style.copy(strikethrough = value) }
                    t
                }
                else -> null
            }
            if (transform != null) {
                for (i in s until e) styles[i] = transform(styles[i])
            }
        }

        val runs = mergeRuns(styles)
        val paragraphs = mapParagraphs(tc, n)
        return org.samesame.notes.core.model.richtext.RichTextOps.normalise(
            RichText(tc.text, runs, paragraphs),
        )
    }

    private fun mergeRuns(styles: List<CharStyle>): List<TextRun> {
        if (styles.isEmpty()) return emptyList()
        val runs = ArrayList<TextRun>()
        var start = 0
        for (i in 1..styles.size) {
            if (i == styles.size || styles[i] != styles[start]) {
                runs += TextRun(i - start, styles[start])
                start = i
            }
        }
        return runs
    }

    private fun mapParagraphs(tc: TextCommon, length: Int): List<ParagraphStyle> {
        val count = tc.text.count { it == '\n' } + 1
        val paras = MutableList(count) { ParagraphStyle() }
        fun paragraphIndexAt(offset: Int): Int {
            val end = offset.coerceIn(0, length)
            var k = 0
            for (i in 0 until end) if (tc.text[i] == '\n') k++
            return k
        }
        for (record in tc.paragraphs) {
            val p = LeReader(record.raw, 12)
            val s = record.start.coerceIn(0, length)
            val e = record.end.coerceIn(0, length)
            val transform: ((ParagraphStyle) -> ParagraphStyle)? = when (record.kind) {
                2 -> run {
                    val level = p.i32().coerceIn(0, Limits.MAX_INDENT_LEVEL)
                    val t: (ParagraphStyle) -> ParagraphStyle = { style -> style.copy(indent = level) }
                    t
                }
                3 -> run {
                    val align = when (p.i32()) {
                        1 -> TextAlign.Right
                        2 -> TextAlign.Center
                        else -> TextAlign.Left
                    }
                    val t: (ParagraphStyle) -> ParagraphStyle = { style -> style.copy(align = align) }
                    t
                }
                5 -> run {
                    val bulletKind = p.i32()
                    val extra = p.i32()
                    when (bulletKind) {
                        2 -> { style: ParagraphStyle -> style.copy(list = ListKind.Checklist, checked = extra == 1) }
                        4, 5, 6, 7 -> { style: ParagraphStyle -> style.copy(list = ListKind.Numbered) }
                        1, 3, 8 -> { style: ParagraphStyle -> style.copy(list = ListKind.Bullet) }
                        else -> null
                    }
                }
                else -> null
            }
            if (transform != null) {
                for (off in s..e) {
                    val idx = paragraphIndexAt(off).coerceIn(0, count - 1)
                    paras[idx] = transform(paras[idx])
                }
            }
        }
        return paras
    }

    private fun ratioOf(page: PageRecord): PageRatio {
        if (page.width <= 0) return PageRatio.A4Portrait
        val hOverW = page.height.toFloat() / page.width.toFloat()
        return listOf(PageRatio.A4Portrait, PageRatio.A4Landscape, PageRatio.Long)
            .minBy { kotlin.math.abs(it.heightOverWidth - hOverW) }
    }
}

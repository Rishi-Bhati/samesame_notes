package org.samesame.notes.core.data.repository

import org.samesame.notes.core.export.ExportSource
import org.samesame.notes.core.export.render.renderPageImages
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.sdocx.SdocxArchive
import org.samesame.notes.core.sdocx.SdocxDocumentBuilder
import org.samesame.notes.core.sdocx.SdocxPageImageFallback
import org.samesame.notes.core.sdocx.map.SdocxMapper
import org.samesame.notes.core.sdocx.SdocxVoiceList
import org.samesame.notes.core.sdocx.SdocxWriter
import java.io.OutputStream

/**
 * `.sdocx` export, in two flavours:
 * - **Imported notes** ([exportImported]) reuse the original archive kept in the bundle's `sdocx/`
 *   side-channel (spec §12.5), so unknown objects survive byte-for-byte; only the title/body text is
 *   overlaid when it was edited.
 * - **Native notes** ([exportNative]) build a fresh minimal archive (spec §13.3) and embed each page
 *   as a PNG through [SdocxPageImageFallback], since the stroke payload layout is still unknown.
 */
internal object SdocxExport {

    const val EXTENSION = "sdocx"
    const val MIME = "application/octet-stream"

    private fun nowUs(): Long = System.currentTimeMillis() * 1000

    /** Lossy native export: text boxes + one full-page image per page. */
    suspend fun exportNative(source: ExportSource, out: OutputStream, lastViewedPageIndex: Int = 0) {
        val document = source.document()
        val base = SdocxDocumentBuilder.toArchive(document, nowUs(), lastViewedPageIndex = lastViewedPageIndex)
        val pageImages = renderPageImages(source)
        val withPages = SdocxPageImageFallback.apply(base, pageImages, nowUs = ::nowUs)
        // Note-level recordings become the note's voice-data list (spec §3.4 bit 13, objects §7).
        val audio = document.recordings.mapNotNull { r -> source.media(r.media)?.let { r.id to it } }.toMap()
        val archive = SdocxVoiceList.apply(withPages, document.recordings, nowUs()) { audio[it.id] }
        SdocxWriter().write(archive, out)
    }

    /** Full-fidelity export of an imported note, overlaying edited title/body text. */
    fun exportImported(
        archive: SdocxArchive,
        document: NoteDocument,
        out: OutputStream,
        originalBytes: ByteArray? = null,
        /** Audio bytes of recordings added in SameSame, keyed by [org.samesame.notes.core.model.document.NoteRecording.id]. */
        recordingAudio: Map<String, ByteArray> = emptyMap(),
        /** The note's stored page, or null to keep the archive's own value. */
        lastViewedPageIndex: Int? = null,
    ) {
        val now = nowUs()
        val textEdited = SdocxMapper.applyDocumentEdits(archive, document, now, lastViewedPageIndex = lastViewedPageIndex)
        val edited = SdocxVoiceList.apply(textEdited, document.recordings, now) { recordingAudio[it.id] }
        if (edited === archive && originalBytes != null) {
            out.write(originalBytes)
            out.flush()
        } else {
            SdocxWriter().write(edited, out)
        }
    }
}

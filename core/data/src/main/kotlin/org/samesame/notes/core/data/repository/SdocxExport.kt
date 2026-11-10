package org.samesame.notes.core.data.repository

import org.samesame.notes.core.document.sdocx.SdocxInkBridge
import org.samesame.notes.core.export.ExportSource
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.floating
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.TextBoxObject
import org.samesame.notes.core.model.document.bitmapMedia
import org.samesame.notes.core.model.richtext.RichTextOps
import org.samesame.notes.core.sdocx.SdocxImageSource
import org.samesame.notes.core.sdocx.SdocxNativeInk
import org.samesame.notes.core.sdocx.SdocxPageStroke
import org.samesame.notes.core.sdocx.SdocxStrokeMerge
import org.samesame.notes.core.sdocx.SdocxArchive
import org.samesame.notes.core.sdocx.SdocxDocumentBuilder
import org.samesame.notes.core.sdocx.SdocxLinkChannel
import org.samesame.notes.core.sdocx.map.SdocxMapper
import org.samesame.notes.core.sdocx.SdocxVoiceList
import org.samesame.notes.core.sdocx.SdocxWriter
import java.io.OutputStream

/**
 * `.sdocx` export, in two flavours:
 * - **Imported notes** ([exportImported]) reuse the original archive kept in the bundle's `sdocx/`
 *   side-channel (spec §12.5), so unknown objects survive byte-for-byte; edited title/body text,
 *   pictures added to the text and edited page handwriting are overlaid, every other unit is reused
 *   verbatim. An unedited note is written back as its original bytes.
 * - **Native notes** ([exportNative]) build a fresh archive shaped like a file from the reference
 *   app: handwriting as real stroke records, pictures as inline image objects in the body text.
 */
internal object SdocxExport {

    const val EXTENSION = "sdocx"
    const val MIME = "application/octet-stream"

    private fun nowUs(): Long = System.currentTimeMillis() * 1000

    /**
     * Native export. A real file is only known to hold pictures inside the body text, so that is
     * where every picture goes: the note's inline pictures stay at their place, pictures and drawings
     * floating on a page are appended to the text in page order (their position on the page is not
     * kept), and the text of floating text boxes is appended as paragraphs. Page templates and PDF
     * backgrounds are not written.
     */
    suspend fun exportNative(source: ExportSource, out: OutputStream, lastViewedPageIndex: Int = 0) {
        val document = source.document()
        val now = nowUs()
        val scale = Limits.PAGE_WIDTH_PU / SdocxDocumentBuilder.PAGE_WIDTH_PX.toFloat()
        val contents = document.pages.associate { spec -> spec.id to source.page(spec.id) }

        val images = HashMap<String, SdocxImageSource>()
        suspend fun register(id: String, media: String, width: Float, height: Float): Boolean {
            val bytes = source.media(media)?.takeIf { it.isNotEmpty() } ?: return false
            if (!(width > 0f) || !(height > 0f)) return false
            images[id] = SdocxImageSource(bytes, extensionOf(media), (width / scale).toDouble(), (height / scale).toDouble())
            return true
        }
        for (obj in document.inlineObjects) {
            if (obj is ImageObject) register(obj.id, obj.media, obj.widthPu, obj.heightPu)
        }
        var body = document.body
        fun newParagraph() {
            if (body.text.isNotEmpty()) body = RichTextOps.insert(body, body.text.length, "\n")
        }
        for (spec in document.pages) {
            for (element in contents.getValue(spec.id).elements) {
                val floating = (element as? ObjectElement)?.floating() ?: continue
                val obj = floating.obj
                val media = obj.bitmapMedia
                if (media != null) {
                    val id = "page-${spec.id}-${element.id.value}"
                    if (register(id, media, floating.rect.width, floating.rect.height)) {
                        newParagraph()
                        body = RichTextOps.insertObject(body, body.text.length, id)
                    }
                } else if (obj is TextBoxObject && obj.text.text.isNotBlank()) {
                    newParagraph()
                    body = RichTextOps.insertRich(body, body.text.length, obj.text)
                }
            }
        }

        val pageInk = contents.mapValues { (_, content) -> SdocxInkBridge.samples(content) }
        val inked = SdocxNativeInk.export(
            document.copy(body = body), pageInk, now, lastViewedPageIndex = lastViewedPageIndex, inlineImages = images,
        )
        // Note-level recordings become the note's voice-data list (spec §3.4 bit 13, objects §7).
        val audio = document.recordings.mapNotNull { r -> source.media(r.media)?.let { r.id to it } }.toMap()
        val voiced = SdocxVoiceList.apply(inked, document.recordings, now) { audio[it.id] }
        val imageSha = document.inlineObjects.filterIsInstance<ImageObject>()
            .mapNotNull { img -> source.media(img.media)?.let { img.media to SdocxLinkChannel.sha(it) } }.toMap()
        val archive = SdocxLinkChannel.withEntry(voiced, SdocxLinkChannel.encode(document) { imageSha[it] })
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
        /** sha-256 of an inline image's bytes by its media path, for the link side channel. */
        imageSha: (String) -> String? = { null },
        /** Current strokes of every page that has an ink file of its own, keyed by page id. */
        pageStrokes: Map<String, List<SdocxPageStroke>> = emptyMap(),
        /** Pictures put into the body text in SameSame, by inline object id. */
        newImages: Map<String, SdocxImageSource> = emptyMap(),
    ) {
        val now = nowUs()
        // Page ink first: a stroke the editor did not change keeps its record, and a page without
        // changes keeps the archive as it is.
        val inked = SdocxStrokeMerge.apply(archive, pageStrokes, now)
        val textEdited = SdocxMapper.applyDocumentEdits(
            inked, document, now, lastViewedPageIndex = lastViewedPageIndex, newImages = newImages,
        )
        val voiced = SdocxVoiceList.apply(textEdited, document.recordings, now) { recordingAudio[it.id] }
        // Links and library ids ride along in one extra entry; an unchanged note keeps the archive (and its bytes) as is.
        val edited = SdocxLinkChannel.withEntry(voiced, SdocxLinkChannel.encode(document, imageSha))
        if (edited === archive && originalBytes != null) {
            out.write(originalBytes)
            out.flush()
        } else {
            SdocxWriter().write(edited, out)
        }
    }
}

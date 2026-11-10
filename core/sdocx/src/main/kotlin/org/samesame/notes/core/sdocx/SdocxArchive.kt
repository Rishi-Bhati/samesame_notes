package org.samesame.notes.core.sdocx

import org.samesame.notes.core.sdocx.record.EndTagRecord
import org.samesame.notes.core.sdocx.record.MediaRegistry
import org.samesame.notes.core.sdocx.record.NoteRecord
import org.samesame.notes.core.sdocx.record.PageIdInfo
import org.samesame.notes.core.sdocx.record.PageRecord

/**
 * Fully parsed `.sdocx` note. Holds typed values for everything the spec describes **and** the raw
 * bytes of every opaque/undelimited unit, so [SdocxWriter] can round-trip unknown objects and fields
 * without loss (spec §12).
 *
 * A page is identified by its 36-char `pageId`; [pages] is in page order (same as [pageIdInfo]).
 */
class SdocxArchive(
    val endTag: EndTagRecord,
    val note: NoteRecord,
    val pageIdInfo: PageIdInfo,
    val pages: List<PageRecord>,
    val media: MediaRegistry,
    /** `media/<name>` zip entry bytes, keyed by the entry name exactly as it appears in the ZIP. */
    val mediaFiles: Map<String, ByteArray>,
    /** Any other ZIP entry we do not model, preserved verbatim. */
    val extraEntries: Map<String, ByteArray>,
    /**
     * The copy of the end tag that files from the reference app carry after the ZIP's end record
     * (the file's last bytes are the end-tag magic). It equals `end_tag.bin` except that its two
     * display times are in milliseconds. Null for an archive built here; [SdocxWriter] always
     * appends one and derives it from [endTag] when this is null.
     */
    val trailer: EndTagRecord? = null,
) {
    val isLocked: Boolean get() = endTag.isLocked
    val ownerId: String get() = endTag.ownerId

    fun page(pageId: String): PageRecord? = pages.firstOrNull { it.pageId == pageId }

    /** All media entries, keyed by bindNumber. */
    val mediaByBindNumber: Map<Int, org.samesame.notes.core.sdocx.record.MediaEntry>
        get() = media.entries.associateBy { it.bindNumber }

    fun withEndTag(tag: EndTagRecord): SdocxArchive = rebuilt(endTag = tag)

    /** A copy with some parts replaced; everything else, including [trailer], is carried over. */
    fun rebuilt(
        endTag: EndTagRecord = this.endTag,
        note: NoteRecord = this.note,
        pages: List<PageRecord> = this.pages,
        media: MediaRegistry = this.media,
        mediaFiles: Map<String, ByteArray> = this.mediaFiles,
        extraEntries: Map<String, ByteArray> = this.extraEntries,
    ): SdocxArchive = SdocxArchive(endTag, note, pageIdInfo, pages, media, mediaFiles, extraEntries, trailer)
}

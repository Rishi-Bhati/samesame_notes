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
) {
    val isLocked: Boolean get() = endTag.isLocked
    val ownerId: String get() = endTag.ownerId

    fun page(pageId: String): PageRecord? = pages.firstOrNull { it.pageId == pageId }

    /** All media entries, keyed by bindNumber. */
    val mediaByBindNumber: Map<Int, org.samesame.notes.core.sdocx.record.MediaEntry>
        get() = media.entries.associateBy { it.bindNumber }

    fun withEndTag(tag: EndTagRecord): SdocxArchive =
        SdocxArchive(tag, note, pageIdInfo, pages, media, mediaFiles, extraEntries)
}

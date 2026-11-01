package org.samesame.notes.core.sdocx

/** Constants of the `.sdocx` format, per `research/sdocx-format-spec*.md`. */
object SdocxFormat {
    /** Trailer magic in `end_tag.bin` (22 ASCII bytes, no terminator). */
    const val END_TAG_MAGIC = "Document for S-Pen SDK"

    /** Page magic after the page hash (26 ASCII bytes). */
    const val PAGE_MAGIC = "Page for SAMSUNG S-Pen SDK"

    /** Version gate (§1.1): reject below (legacy) and above (unsupported). */
    const val MIN_SUPPORTED_VERSION = 2034
    const val MAX_SUPPORTED_VERSION = 4000

    /** Version we write everywhere; stays in the "basic" mediaInfo form. */
    const val WRITE_FORMAT_VERSION = 3001

    const val PAGE_ID_LENGTH = 36

    /** `note.note` uses `i32` length prefixes for the title/body text-box objects. */
    const val NOTE_TITLE_PREFIX = "note.note"
    const val NOTE_PAGE_ID_INFO = "pageIdInfo.dat"
    const val NOTE_MEDIA_INFO = "media/mediaInfo.dat"
    const val NOTE_END_TAG = "end_tag.bin"

    /**
     * Object type codes inside a layer (§7). Types 5, 6, 9, 16 are reserved/unsupported.
     */
    object ObjectType {
        const val STROKE = 1
        const val TEXT_BOX = 2
        const val IMAGE = 3
        const val CONTAINER = 4
        const val SHAPE = 7
        const val LINE = 8
        const val VOICE = 10
        const val FORMULA = 11
        const val TABLE = 12
        const val WEB_CARD = 13
        const val PAINTING = 14
        const val LEGACY_STROKE = 15
        const val LINK_CARD = 17
    }

    /**
     * `end_tag.bin` `documentType` enum (§9): 0 unlocked, 5 = locked `.sdocx`. 1..4 are legacy
     * locked formats and are not supported by this reader.
     */
    object DocumentType {
        const val UNLOCKED = 0
        const val LOCKED_SDOCX = 5
    }

    /** `pageMode` (§2): 0 = list / continuous scroll, 1 = single page. */
    object PageMode {
        const val LIST = 0
        const val SINGLE = 1
    }
}

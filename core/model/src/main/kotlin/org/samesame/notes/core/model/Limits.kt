package org.samesame.notes.core.model

/** Product limits, kept in one place so UI, data and tests agree. */
object Limits {
    const val TRASH_RETENTION_DAYS = 30
    const val MAX_FOLDER_DEPTH = 50
    const val MAX_FAVORITE_PENS = 45
    const val MAX_TOOLBAR_ITEMS = 11
    const val MAX_SEARCH_QUERY_LENGTH = 199

    // Editor v2 (docs/editor-architecture.md, WP-A)
    const val MAX_UNDO_STEPS = 200
    const val MAX_ZOOM = 3f
    const val MAX_INDENT_LEVEL = 5 // NOTE(WP-A): value not specified in the doc; provisional
    const val PAGE_WIDTH_PU = 1080f
    const val FONT_PU_PER_POINT = 2.625f
    const val DEFAULT_FONT_SIZE = 17f

    // Voice recording (O5). Values are the reference app's recorder constants (VoiceManager / MediaRecorderConfig, read only).
    const val VOICE_MAX_DURATION_MS = 21_600_000 // 6 hours
    const val VOICE_MAX_FILE_BYTES = 524_288_000L // 500 MiB
    const val VOICE_SAMPLE_RATE_HZ = 44_100
    const val VOICE_BIT_RATE = 128_000
    const val VOICE_CHANNELS = 2
    /** Extension of a stored recording (`media/<name>.m4a`); the reference's voice extension. The container is 3GPP (see the recorder). */
    const val VOICE_FILE_EXTENSION = "m4a"
    /** Bundle extension of a drawing's stroke payload (a `PageLayerCodec` page in sheet space). */
    const val DRAWING_INK_EXTENSION = "drw"
    const val VOICE_SEEK_STEP_MS = 10_000 // rewind / forward button
    /** A recording shorter than this is discarded ("Recording too short"). the reference's native threshold is unknown; provisional. */
    const val VOICE_MIN_DURATION_MS = 1_000L

    // Untrusted input (security review 2026-10-30): files from other apps are parsed against these caps, so a
    // crafted note fails with an IOException instead of exhausting memory or storage.
    /** Elements (strokes + objects) one ink page may declare. */
    const val MAX_PAGE_ELEMENTS = 250_000
    /** Bytes of one ink-page element payload (one stroke's inputs or one object's JSON). */
    const val MAX_PAGE_ELEMENT_BYTES = 16 * 1024 * 1024
    /** Pixels a media image is decoded to (larger images are downsampled with inSampleSize). */
    const val MAX_DECODE_PIXELS = 4096 * 4096
    /** Size of one incoming file (a note, .sdocx or PDF) read from another app. */
    const val MAX_IMPORT_BYTES = 2L * 1024 * 1024 * 1024
    /** Uncompressed size of `manifest.json` in a note container. */
    const val MAX_MANIFEST_BYTES = 64 * 1024
    /** Uncompressed size of `document.json` in a note container. */
    const val MAX_DOCUMENT_JSON_BYTES = 32 * 1024 * 1024
    /** Uncompressed size of any other single entry of a note container (above [VOICE_MAX_FILE_BYTES]). */
    const val MAX_ARCHIVE_ENTRY_BYTES = 768L * 1024 * 1024
    /** Total uncompressed size of one note container; the inflate ratio below is the tighter bound in practice. */
    const val MAX_ARCHIVE_TOTAL_BYTES = 16L * 1024 * 1024 * 1024
    /**
     * An archive may inflate to at most this multiple of its compressed size (plus [MAX_ARCHIVE_INFLATE_SLACK_BYTES]);
     * deflate reaches about 1000:1 on zeros, real notes stay far below 100:1.
     */
    const val MAX_ARCHIVE_INFLATE_RATIO = 100L
    const val MAX_ARCHIVE_INFLATE_SLACK_BYTES = 64L * 1024 * 1024
    /** A .sdocx is parsed in memory: the archive itself may be at most this large ... */
    const val MAX_SDOCX_BYTES = 256L * 1024 * 1024
    /** ... and inflate to at most this much in total. */
    const val MAX_SDOCX_INFLATED_BYTES = 512L * 1024 * 1024
    /** Entries in one note container or .sdocx archive. */
    const val MAX_ARCHIVE_ENTRIES = 20_000
    /** Total uncompressed bytes allowed for an archive of [compressedBytes]. */
    fun maxInflatedBytes(compressedBytes: Long): Long =
        minOf(MAX_ARCHIVE_TOTAL_BYTES, compressedBytes * MAX_ARCHIVE_INFLATE_RATIO + MAX_ARCHIVE_INFLATE_SLACK_BYTES)

    /** Nesting depth of grouped objects in a .sdocx page. */
    const val MAX_SDOCX_OBJECT_DEPTH = 32

    // Files library (0.2.0, docs/architecture/files-feature.md 1.2). Files in the Recycle bin reuse TRASH_RETENTION_DAYS.
    /** Size of one file imported into Files. */
    const val MAX_LIBRARY_FILE_BYTES = 2L * 1024 * 1024 * 1024
    /** An import stops when the free space on the data partition would drop below this. */
    const val LIBRARY_FREE_SPACE_RESERVE_BYTES = 200L * 1024 * 1024
    /** UTF-8 bytes of a file's display name (the usual file-system limit). */
    const val MAX_FILE_NAME_BYTES = 255
    /** Characters (code points) of a Files folder name. */
    const val MAX_FILE_FOLDER_NAME_LENGTH = 50
}

package org.samesame.notes.core.model

/** Product limits, kept in one place so UI, data and tests agree. */
object Limits {
    const val TRASH_RETENTION_DAYS = 30
    const val MAX_FOLDER_DEPTH = 50
    const val MAX_FAVORITE_PENS = 45
    const val MAX_TOOLBAR_ITEMS = 11
    const val MAX_SEARCH_QUERY_LENGTH = 199

    /** Largest font file Settings > Font accepts for "Add your own font". */
    const val MAX_CUSTOM_FONT_BYTES = 10 * 1024 * 1024

    /** Rows the note-list widget shows (RemoteViews collections stay small and cheap to rebuild). */
    const val WIDGET_LIST_MAX_ROWS = 30

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
    /** A picture larger than this is not opened as a note picture (kept in Files only). */
    const val MAX_IMAGE_IMPORT_BYTES = 64L * 1024 * 1024
    /** A text file larger than this is not opened as a note (it is kept in Files instead). */
    const val MAX_TEXT_IMPORT_BYTES = 2L * 1024 * 1024
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

    // Calculate as you type (F6). Caps keep a typed expression from producing a huge result or a slow parse.
    /** Longest line prefix (characters before the caret) that is evaluated at all. */
    const val MATH_MAX_EXPRESSION_LENGTH = 240
    /** Largest exponent accepted by `^` in either direction; every result is also checked against the digit caps. */
    const val MATH_MAX_EXPONENT = 64
    /** Digits before the decimal point of a result; a bigger magnitude counts as overflow. */
    const val MATH_MAX_RESULT_DIGITS = 40
    /** Decimal places a result is rounded to before trailing zeros are stripped. */
    const val MATH_MAX_DECIMALS = 10
    /** Item-block lines inspected for a keyword total, including skipped labels. */
    const val MATH_BLOCK_MAX_LINES = 200
    /** Line cap for the plain-number aggregate API. */
    const val MATH_MAX_AGGREGATE_LINES = MATH_BLOCK_MAX_LINES

    // Files library (0.2.0, docs/architecture/files-feature.md 1.2). Files in the Recycle bin reuse TRASH_RETENTION_DAYS.
    /** Size of one file imported into Files. */
    const val MAX_LIBRARY_FILE_BYTES = 2L * 1024 * 1024 * 1024
    /** An import stops when the free space on the data partition would drop below this. */
    const val LIBRARY_FREE_SPACE_RESERVE_BYTES = 200L * 1024 * 1024
    /** UTF-8 bytes of a file's display name (the usual file-system limit). */
    const val MAX_FILE_NAME_BYTES = 255
    /** Characters (code points) of a Files folder name. */
    const val MAX_FILE_FOLDER_NAME_LENGTH = 50
    /** "Show in note" copies the picture into the note; a bigger file is pasted as a link instead. */
    const val MAX_SHOW_IN_NOTE_BYTES = 64L * 1024 * 1024
    /** Files pasted or inserted at once (the reference's paste/share cap). */
    const val MAX_FILES_PER_PASTE = 100
    /** Code points of a file's short description (Files Info sheet). */
    const val MAX_FILE_DESCRIPTION_LENGTH = 500
    /** Code points of one file tag. */
    const val MAX_FILE_TAG_LENGTH = 30
    /** Tags one file may carry. */
    const val MAX_FILE_TAGS = 20
    /** Files one Upload folder takes; a larger tree reports the rest as skipped. */
    const val MAX_FILES_PER_UPLOAD = 1_000

    // Markdown notes (0.4.0)
    /** Source characters one Markdown note holds; a larger file opens as plain text without the live preview. */
    const val MAX_MARKDOWN_CHARS = 1_000_000
    /** Block nesting the parser walks; deeper nodes are dropped so a hostile file cannot overflow the stack. */
    const val MAX_MARKDOWN_AST_DEPTH = 64
    /** Blocks the preview composes; the rest is cut off. */
    const val MAX_MARKDOWN_PREVIEW_NODES = 10_000
    const val MAX_MARKDOWN_TABLE_ROWS = 500
    const val MAX_MARKDOWN_TABLE_COLS = 50
    /** Characters of one code block the preview shows. */
    const val MAX_MARKDOWN_CODE_CHARS = 50_000
    // Text documents (0.4.0 phase 3.1). Larger files open read-only so a cut-off copy never overwrites the original.
    const val MAX_TEXTDOC_EDIT_BYTES = 5L * 1024 * 1024
    /** Longest line the editor lays out at once; longer lines are cut into rows to keep Compose responsive. */
    const val MAX_TEXTDOC_LINE_CHARS = 4000
    /** Find stops after this many hits; the editor is not a full text search engine. */
    const val MAX_TEXTDOC_FIND_MATCHES = 5000
    /** Snapshot text memory (in bytes, chars * 2) kept across undo/redo history; older snapshots are dropped. */
    const val MAX_TEXTDOC_UNDO_BYTES = 32L * 1024 * 1024

    // Markdown export and conversion (Phase 3.3)
    const val MARKDOWN_EXPORT_MAX_BYTES = 16L * 1024 * 1024 // 16 MiB
    const val MARKDOWN_CONVERSION_TIMEOUT_MS = 10_000L // 10s
    const val MARKDOWN_MAX_NESTING_DEPTH = 32
    const val PDF_RENDER_TIMEOUT_MS = 30_000L // 30s
    // Automatic version history (0.3.2). A version is a copy of a note's packed container.
    /** A new version is taken on save only when the newest one is at least this old (closing the note always takes one). */
    const val VERSION_MIN_INTERVAL_MS = 5 * 60_000L
    /** Versions kept per note; the oldest go first. */
    const val MAX_VERSIONS_PER_NOTE = 50
    /** Versions older than this are pruned, except the newest [VERSIONS_ALWAYS_KEPT] of a note. */
    const val VERSION_RETENTION_DAYS = 30
    /** The newest versions of a note that age never removes. */
    const val VERSIONS_ALWAYS_KEPT = 5
    /** All versions of all notes together; beyond it the oldest are removed (the newest of each note stays). */
    const val MAX_VERSION_BYTES_TOTAL = 500L * 1024 * 1024
    /** Characters of body text shown in the read-only version preview. */
    const val VERSION_PREVIEW_CHARS = 20_000

    // PDF engine, markup and page tools (0.5.0)
    /** Pages one imported PDF may add to a note (same cap the importer already uses). */
    const val PDF_MAX_PAGES = 300
    /** Largest PDF the app opens, imports or unlocks. */
    const val PDF_MAX_FILE_BYTES = 256L * 1024 * 1024
    /** Edge of one square render tile, in px. */
    const val PDF_TILE_SIZE_PX = 512
    /** Tile bitmap memory kept by the LRU cache (48 tiles of 512x512 ARGB_8888). */
    const val PDF_TILE_CACHE_MAX_BYTES = 48L * 1024 * 1024
    /** Tiles are drawn only above this zoom; at or below it the base page bitmap is enough. */
    const val PDF_TILE_ZOOM_THRESHOLD = 1.25f
    /** Pages of extracted text kept in memory per open PDF. */
    const val PDF_TEXT_CACHE_MAX_PAGES = 100
    /** Outline levels read; deeper entries are dropped so a hostile file cannot blow the stack. */
    const val PDF_MAX_OUTLINE_DEPTH = 10
    /** Wrong-password tries in the unlock dialog before it closes. */
    const val PDF_PASSWORD_MAX_ATTEMPTS = 5
    /** In-note PDF search stops after this many hits. */
    const val PDF_SEARCH_MAX_MATCHES = 1000
    /** Text markups (highlight, underline, strike) one page keeps. */
    const val MAX_MARKUPS_PER_PAGE = 5_000
    /** Quads (one per text line) one markup keeps. */
    const val MAX_QUADS_PER_MARKUP = 1_000
    /** Characters of selected text stored with one markup. */
    const val MAX_MARKUP_TEXT_LENGTH = 10_000
    /** Heap portion of each PdfBox scratch store; larger streams spill to disk. */
    const val PDF_SCRATCH_MEMORY_BYTES = 8L * 1024 * 1024
    /** Vector PDF export gives up after this long. */
    const val PDF_VECTOR_EXPORT_TIMEOUT_MS = 60_000L
}

package org.samesame.notes.core.model.document

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.richtext.RichText

// NOTE(WP-A): PageMode, PageRatio and PageTemplate already exist in org.samesame.notes.core.model
// (Note.kt / Page.kt) and are reused instead of being redefined here.

/**
 * Version of `document.json` written by this build. v3 added [TextBoxObject], [VoiceObject] and
 * [FloatingObject.rotationDeg]/[FloatingObject.wrap]; v4 added [NoteDocument.recordings]; v5 added [PaintingObject]
 * (Insert > Drawing; its strokes are a `media/<sha256>.drw` payload); v6 added [PageSpec.size] (per-page size, imported
 * PDF pages keep their own shape); v7 moved the reading position out of the document into the per-note view-state
 * store, so reading and scrolling never rewrite a note (a v6 document's field is still read for the migration, see
 * NoteDocumentMigrations.v2ToV3 / v3ToV4 / v4ToV5 / v5ToV6 / v6ToV7).
 */
const val NOTE_DOCUMENT_FORMAT_VERSION = 7

/** Shared JSON configuration for document structures (defaults are written, unknown keys ignored). */
val NoteJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

@Serializable
data class NoteDocument(
    val formatVersion: Int = NOTE_DOCUMENT_FORMAT_VERSION,
    val id: String,
    val title: String = "",
    val layout: PageLayout = PageLayout(),
    val style: NoteStyle = NoteStyle(),
    val body: RichText = RichText(),
    /** Referenced from the body by `CharStyle.objectId`. */
    val inlineObjects: List<NoteObject> = emptyList(),
    /** Note-level voice recordings, shown in the recording bar's list. */
    val recordings: List<NoteRecording> = emptyList(),
    val pages: List<PageSpec>,
) {
    fun toJson(): String = NoteJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): NoteDocument = NoteJson.decodeFromString(serializer(), json)
    }
}

/**
 * A note-level voice recording. Unlike a
 * [VoiceObject] (an embedded on-page player chip) this is not placed on any page: it lives in the
 * note's recording list and the recording bar.
 */
@Serializable
data class NoteRecording(
    val id: String,
    /** `media/<sha256>.<ext>` in the bundle. */
    val media: String,
    /** Display name; empty falls back to the generated "Voice recording N" in the UI. */
    val name: String = "",
    /** Wall-clock creation time (ms). */
    val createdAtMs: Long = 0L,
    /** Elapsed recording time (ms); 0 = unknown, then [displayDuration] is the fallback. */
    val elapsedMs: Long = 0L,
    /** Original display duration text (sdocx `playTime`, e.g. "0:18"). */
    val displayDuration: String = "",
    /** sdocx media bind number this recording came from (keeps re-export metadata stable); null for native ones. */
    val sourceFileId: Int? = null,
    /** Recording timeline (pause/resume stamps), kept for voice-sync replay. */
    val timeline: List<VoiceTimelineEvent> = emptyList(),
) {
    /** Playable duration: [elapsedMs] when known, else [displayDuration] parsed. */
    val durationMs: Long
        get() = if (elapsedMs > 0L) elapsedMs else parseDisplayDurationMs(displayDuration)
}

/** One pause/resume/stop stamp of a recording (sdocx `VoiceProperty`; actions 0 none, 1 start, 2 pause, 3 resume, 4 stop). */
@Serializable
data class VoiceTimelineEvent(val action: Int, val timestampMs: Long)

/** "mm:ss" / "h:mm:ss" display text to milliseconds; 0 when unparseable (sdocx `VoiceUtil.changeTimeType`). */
fun parseDisplayDurationMs(text: String): Long {
    if (text.isBlank()) return 0L
    val fields = text.trim().split(':')
    if (fields.size !in 1..3) return 0L
    var seconds = 0L
    for (field in fields) {
        if (field.isEmpty() || field.any { !it.isDigit() }) return 0L
        seconds = seconds * 60L + (field.toLongOrNull() ?: return 0L)
    }
    return seconds * 1000L
}

@Serializable
enum class ScrollDirection { Vertical, Horizontal }

@Serializable
data class PageLayout(
    val mode: PageMode = PageMode.Pages,
    val ratio: PageRatio = PageRatio.A4Portrait,
    val scroll: ScrollDirection = ScrollDirection.Vertical,
)

@Serializable
data class NoteStyle(
    /** null = "default". */
    val backgroundColor: Long? = null,
    val adjustForDarkMode: Boolean = true,
)

@Serializable
sealed interface TemplateRef {
    @Serializable @SerialName("builtin")
    data class Builtin(val template: PageTemplate) : TemplateRef

    /** NOTE(WP-A): not in the doc; reserved for the Images tab of the template picker (media path). */
    @Serializable @SerialName("image")
    data class Image(val media: String) : TemplateRef
}

@Serializable
data class PdfBackground(val media: String, val pageIndex: Int)

/**
 * Physical size of one page in PDF points (1/72 inch), format v6. Every page is [org.samesame.notes.core.model.Limits.PAGE_WIDTH_PU]
 * page units wide; its height follows this aspect ([heightOverWidth], clamped to [MIN_HEIGHT_OVER_WIDTH]..[MAX_HEIGHT_OVER_WIDTH]).
 * Imported PDF pages carry their original (rotated) size here; export writes pages of exactly this size.
 */
@Serializable
data class PageSize(val widthPt: Float, val heightPt: Float) {
    /** True when both sides are positive, finite numbers. */
    val isValid: Boolean get() = widthPt > 0f && heightPt > 0f && widthPt.isFinite() && heightPt.isFinite()

    /** Height over width as laid out (clamped); only meaningful when [isValid]. */
    val heightOverWidth: Float get() = clampHeightOverWidth(heightPt / widthPt)

    companion object {
        /** Wider pages are letterboxed into this shape (a page must still hold one line of text between its margins). */
        const val MIN_HEIGHT_OVER_WIDTH = 0.2f
        /** Taller pages are letterboxed into this shape (keeps the page layers and background renders bounded). */
        const val MAX_HEIGHT_OVER_WIDTH = 10f

        /** A4 portrait in points, the physical size of a page without its own size. */
        const val A4_WIDTH_PT = 595f
        const val A4_HEIGHT_PT = 842f

        fun clampHeightOverWidth(r: Float): Float =
            if (!r.isFinite() || r <= 0f) 1f else r.coerceIn(MIN_HEIGHT_OVER_WIDTH, MAX_HEIGHT_OVER_WIDTH)
    }
}

@Serializable
data class PageSpec(
    val id: String,
    val template: TemplateRef = TemplateRef.Builtin(PageTemplate.Blank),
    /** Per-page override; null = [NoteStyle.backgroundColor]. */
    val backgroundColor: Long? = null,
    val isBookmarked: Boolean = false,
    val pdf: PdfBackground? = null,
    /** Own page size (v6); null = the note's [PageLayout.ratio], which is how every page before v6 is read. */
    val size: PageSize? = null,
) {
    /** Height over width of this page as laid out in [layout]. */
    fun heightOverWidth(layout: PageLayout): Float =
        size?.takeIf { it.isValid }?.heightOverWidth ?: layout.ratio.heightOverWidth

    /**
     * The spec of a new page added after this one (Add page, a page appended by overflowing text or an infinite note):
     * same size, colour and template, no bookmark. The background of an imported PDF page is not copied: the new page
     * starts blank.
     */
    fun follower(newId: String): PageSpec = copy(
        id = newId,
        isBookmarked = false,
        pdf = null,
        template = if (pdf != null && template is TemplateRef.Image) TemplateRef.Builtin(PageTemplate.Blank) else template,
    )
}

/** Height over width of page [index] of this document (pages past the end use the note's ratio). */
fun NoteDocument.pageHeightOverWidth(index: Int): Float =
    pages.getOrNull(index)?.heightOverWidth(layout) ?: layout.ratio.heightOverWidth

@Serializable
data class ViewState(val pageIndex: Int, val offsetInPage: Float, val zoom: Float)

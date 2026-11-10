package org.samesame.notes.core.export

import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.richtext.RichText

/** Everything the "Save as file" dialog offers. */
enum class ExportFormat {
    /** Native single-file container; handled by the repository (delegates to the document store). */
    SSNOTE,
    PDF,
    /** PNG or JPEG: one file per page (zipped when there are several) or one tall image. */
    IMAGES,
    TXT,
    /** `.md` alone, or a `.zip` of md + media (see [ExportOptions.markdownAsZip]). */
    MARKDOWN,
    HTML,
    DOCX,
    PPTX,
    /** Reserved: implemented elsewhere from the sdocx format spec, registered through [NoteExporter.register]. */
    SDOCX,
}

enum class ImageFormat(val extension: String, val mimeType: String) {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
}

enum class ImageBackground {
    /** Page colour and template artwork (lines, grid, ...). */
    Template,
    /** Plain white. */
    White,
    /** Nothing under the content (PNG only; JPEG falls back to white). */
    Transparent,
}

enum class ImageLayout {
    /** One tall image for infinite notes, one image per page for paged notes. */
    Auto,
    PerPage,
    SingleTall,
}

data class ImageOptions(
    val format: ImageFormat = ImageFormat.PNG,
    /** Pixels per page unit; 1 = 1080 px wide. */
    val scale: Float = 1f,
    val background: ImageBackground = ImageBackground.Template,
    val layout: ImageLayout = ImageLayout.Auto,
    val jpegQuality: Int = 92,
)

data class ExportOptions(
    val image: ImageOptions = ImageOptions(),
    /** MARKDOWN: write a zip with `<title>.md` and the media folder instead of a bare `.md`. */
    val markdownAsZip: Boolean = false,
    /** Export only these pages, in note order (the page sorter's Share / Save as file). Null = the whole note. */
    val pageIds: Set<String>? = null,
    /** SSNOTE: bundle the library files the note links to (container v3). On by default; other formats ignore it. */
    val includeLinkedFiles: Boolean = true,
)

/** File name extension and MIME type of what an export writes. */
data class ExportTarget(val extension: String, val mimeType: String) {
    fun fileName(title: String): String = "${sanitizeFileName(title)}.$extension"
}

fun sanitizeFileName(title: String): String {
    val cleaned = title.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80).trim('.', ' ')
    return cleaned.ifEmpty { "Note" }
}

/**
 * The target an export is expected to produce, for the file picker's suggested name. [pageCount] is
 * `document.pages.size`. The authoritative target is the one [NoteExporter.export] returns (an
 * infinite note's blank trailing segments are trimmed, which can turn a zip into a single image).
 */
fun expectedTarget(format: ExportFormat, options: ExportOptions, infinite: Boolean, pageCount: Int): ExportTarget = when (format) {
    ExportFormat.SSNOTE -> ExportTarget("ssnote", "application/zip")
    ExportFormat.SDOCX -> ExportTarget("sdocx", "application/octet-stream")
    ExportFormat.PDF -> ExportTarget("pdf", "application/pdf")
    ExportFormat.TXT -> ExportTarget("txt", "text/plain")
    ExportFormat.HTML -> ExportTarget("html", "text/html")
    ExportFormat.DOCX -> ExportTarget(
        "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )
    ExportFormat.PPTX -> ExportTarget(
        "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )
    ExportFormat.MARKDOWN ->
        if (options.markdownAsZip) ExportTarget("zip", "application/zip") else ExportTarget("md", "text/markdown")
    ExportFormat.IMAGES -> {
        val tall = when (options.image.layout) {
            ImageLayout.SingleTall -> true
            ImageLayout.PerPage -> false
            ImageLayout.Auto -> infinite
        }
        if (tall || pageCount <= 1) ExportTarget(options.image.format.extension, options.image.format.mimeType)
        else ExportTarget("zip", "application/zip")
    }
}

/** Raw note content, loaded lazily by the caller (the repository). */
interface ExportSource {
    suspend fun document(): NoteDocument
    suspend fun page(pageId: String): PageContent
    /** Bytes of `media/<name>` or null when missing. */
    suspend fun media(path: String): ByteArray?
}

/** An image rendered by [ExportAssets]; always PNG. */
class RenderedImage(val png: ByteArray, val widthPx: Int, val heightPx: Int)

/**
 * What the pure generators (txt, md, html, docx, pptx) need from the Android side. Implemented by the
 * renderer; tests provide fakes.
 */
interface ExportAssets {
    /** Number of rendered pages (infinite notes: trailing blank segments trimmed). */
    val pageCount: Int
    /** Page aspect, height over width. */
    val heightOverWidth: Float
    /** Media bytes normalised to PNG / JPEG / GIF, or null. */
    fun media(path: String): ByteArray?
    /** Strokes and floating images of the page on a transparent background (no text); null when there are none. */
    fun inkLayer(pageIndex: Int): RenderedImage?
    /** The whole page as the user sees it (background, template, text, ink, objects). */
    fun fullPage(pageIndex: Int): RenderedImage
    /** Text of floating text boxes on the page. */
    fun floatingTexts(pageIndex: Int): List<RichText>
    /** Body text that falls on the page plus its floating text boxes, plain. */
    fun pageText(pageIndex: Int): String
}

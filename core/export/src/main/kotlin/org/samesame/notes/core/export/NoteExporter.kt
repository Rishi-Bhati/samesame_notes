package org.samesame.notes.core.export

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.samesame.notes.core.export.ooxml.DocxExport
import org.samesame.notes.core.export.ooxml.MarkdownDocxExport
import org.samesame.notes.core.export.ooxml.PptxExport
import org.samesame.notes.core.export.render.AndroidAssets
import org.samesame.notes.core.export.render.ImageExport
import org.samesame.notes.core.export.render.MarkdownPdfExport
import org.samesame.notes.core.export.render.NoteRenderer
import org.samesame.notes.core.export.render.PdfExport
import org.samesame.notes.core.export.render.loadNote
import org.samesame.notes.core.export.text.HtmlExport
import org.samesame.notes.core.export.text.MarkdownConverter
import org.samesame.notes.core.export.text.MarkdownExport
import org.samesame.notes.core.export.text.MarkdownHtmlExport
import org.samesame.notes.core.export.text.TextExport
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.files.ZipEntryNames
import java.io.OutputStream

/** A format implemented outside this module (SSNOTE by the repository, SDOCX by the importer/exporter agent). */
fun interface FormatExporter {
    suspend fun export(source: ExportSource, options: ExportOptions, out: OutputStream): ExportTarget
}

/**
 * Renders a note into one of the [ExportFormat]s. Stateless apart from the registry of external
 * formats; safe to share. Rendering runs on `Dispatchers.Default`.
 */
class NoteExporter(private val context: Context? = null) {
    private val external = HashMap<ExportFormat, FormatExporter>()

    /**
     * Hook for formats implemented elsewhere.
     * TODO(sdocx): the SDOCX exporter (full round-trip, from research/sdocx-format-spec*.md) is written by
     * another work package and registers itself here with `register(ExportFormat.SDOCX, ...)`.
     */
    fun register(format: ExportFormat, exporter: FormatExporter) { external[format] = exporter }

    /** Writes [format] to [out] and returns what was actually written (name extension / MIME type). */
    suspend fun export(source: ExportSource, format: ExportFormat, options: ExportOptions, out: OutputStream): ExportTarget {
        // A page subset narrows the source once, so every format below sees only those pages.
        val scoped = options.pageIds?.let { PageSubsetSource(source, it) } ?: source
        external[format]?.let { return it.export(scoped, options, out) }
        if (format == ExportFormat.SSNOTE || format == ExportFormat.SDOCX) {
            throw UnsupportedOperationException("$format export is not available (TODO(sdocx) / handled by the repository)")
        }
        val loaded = loadNote(scoped)
        return withContext(Dispatchers.Default) {
            val doc = loaded.doc
            val markdown = doc.markdown

            // Dispatch Markdown notes to Markdown export pipelines
            if (markdown != null) {
                val md = markdown.source
                val target = expectedTarget(format, options, doc.layout.mode == PageMode.Infinite, 1)
                when (format) {
                    ExportFormat.MARKDOWN -> {
                        if (options.markdownAsZip || options.linkFilesAsZip) {
                            val files = linkedFiles(md, scoped)
                            val paths = ZipEntryNames.allocate(files.map { it.id.lowercase() to it.name })
                            BundleZip.write(
                                out, "note.md", BundleZip.rewriteLinks(md, paths),
                                media = emptyMap(), files = files, paths = paths,
                            )
                        } else out.write(md.toByteArray(Charsets.UTF_8))
                    }
                    ExportFormat.HTML -> {
                        val html = MarkdownHtmlExport.render(md, doc.title)
                        if (options.linkFilesAsZip) {
                            val files = linkedFiles(html, scoped)
                            val paths = ZipEntryNames.allocate(files.map { it.id.lowercase() to it.name })
                            BundleZip.write(out, "note.html", BundleZip.rewriteLinks(html, paths), files = files, paths = paths)
                        } else out.write(html.toByteArray(Charsets.UTF_8))
                    }
                    ExportFormat.PDF -> {
                        val html = MarkdownHtmlExport.render(md, doc.title)
                        val ctx = checkNotNull(context) { "Context is required for Markdown PDF export" }
                        MarkdownPdfExport.write(ctx, html, doc.title, out)
                    }
                    ExportFormat.DOCX -> {
                        MarkdownDocxExport.write(md, doc.title, out)
                    }
                    ExportFormat.TXT -> {
                        val text = MarkdownConverter.markdownToPlainText(md)
                        out.write(text.toByteArray(Charsets.UTF_8))
                    }
                    ExportFormat.IMAGES -> {
                        val renderer = NoteRenderer(loaded)
                        return@withContext ImageExport.write(renderer, doc.title, options, out)
                    }
                    ExportFormat.PPTX -> {
                        val renderer = NoteRenderer(loaded)
                        val assets = AndroidAssets(renderer)
                        PptxExport.write(doc, assets, out)
                    }
                    ExportFormat.SSNOTE, ExportFormat.SDOCX -> error("unreachable")
                }
                out.flush()
                return@withContext target
            }

            val renderer = NoteRenderer(loaded)
            val assets = AndroidAssets(renderer)
            val target = expectedTarget(format, options, doc.layout.mode == PageMode.Infinite, renderer.pageCount)
            when (format) {
                ExportFormat.PDF -> PdfExport.write(renderer, out)
                ExportFormat.IMAGES -> return@withContext ImageExport.write(renderer, doc.title, options, out)
                ExportFormat.TXT -> out.write(TextExport.render(doc, assets).toByteArray(Charsets.UTF_8))
                ExportFormat.HTML -> {
                    val html = HtmlExport.render(doc, assets)
                    if (options.linkFilesAsZip) {
                        val files = linkedFiles(html, scoped)
                        val paths = ZipEntryNames.allocate(files.map { it.id.lowercase() to it.name })
                        BundleZip.write(out, "note.html", BundleZip.rewriteLinks(html, paths), files = files, paths = paths)
                    } else out.write(html.toByteArray(Charsets.UTF_8))
                }
                ExportFormat.DOCX -> DocxExport.write(doc, assets, out)
                ExportFormat.PPTX -> PptxExport.write(doc, assets, out)
                ExportFormat.MARKDOWN -> {
                    val result = MarkdownExport.render(doc, assets, writeMedia = options.markdownAsZip)
                    if (options.markdownAsZip || options.linkFilesAsZip) {
                        val files = linkedFiles(result.markdown, scoped)
                        val paths = ZipEntryNames.allocate(files.map { it.id.lowercase() to it.name })
                        BundleZip.write(
                            out, "note.md", BundleZip.rewriteLinks(result.markdown, paths),
                            media = result.media, files = files, paths = paths,
                        )
                    } else out.write(result.markdown.toByteArray(Charsets.UTF_8))
                }
                ExportFormat.SSNOTE, ExportFormat.SDOCX -> error("unreachable")
            }
            out.flush()
            target
        }
    }

    /** The linked files actually referenced by the rendered text (missing files are simply not bundled). */
    private suspend fun linkedFiles(text: String, source: ExportSource): List<ExportLinkedFile> {
        val ids = BundleZip.linkedIds(text)
        return if (ids.isEmpty()) emptyList() else source.linkedFiles(ids)
    }
}

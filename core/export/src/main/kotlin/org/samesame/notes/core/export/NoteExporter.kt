package org.samesame.notes.core.export

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.samesame.notes.core.export.ooxml.DocxExport
import org.samesame.notes.core.export.ooxml.PptxExport
import org.samesame.notes.core.export.render.AndroidAssets
import org.samesame.notes.core.export.render.ImageExport
import org.samesame.notes.core.export.render.NoteRenderer
import org.samesame.notes.core.export.render.PdfExport
import org.samesame.notes.core.export.render.loadNote
import org.samesame.notes.core.export.text.HtmlExport
import org.samesame.notes.core.export.text.MarkdownExport
import org.samesame.notes.core.export.text.TextExport
import org.samesame.notes.core.model.PageMode
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A format implemented outside this module (SSNOTE by the repository, SDOCX by the importer/exporter agent). */
fun interface FormatExporter {
    suspend fun export(source: ExportSource, options: ExportOptions, out: OutputStream): ExportTarget
}

/**
 * Renders a note into one of the [ExportFormat]s. Stateless apart from the registry of external
 * formats; safe to share. Rendering runs on `Dispatchers.Default`.
 */
class NoteExporter {
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
            val renderer = NoteRenderer(loaded)
            val assets = AndroidAssets(renderer)
            val doc = loaded.doc
            val target = expectedTarget(format, options, doc.layout.mode == PageMode.Infinite, renderer.pageCount)
            when (format) {
                ExportFormat.PDF -> PdfExport.write(renderer, out)
                ExportFormat.IMAGES -> return@withContext ImageExport.write(renderer, doc.title, options, out)
                ExportFormat.TXT -> out.write(TextExport.render(doc, assets).toByteArray(Charsets.UTF_8))
                ExportFormat.HTML -> out.write(HtmlExport.render(doc, assets).toByteArray(Charsets.UTF_8))
                ExportFormat.DOCX -> DocxExport.write(doc, assets, out)
                ExportFormat.PPTX -> PptxExport.write(doc, assets, out)
                ExportFormat.MARKDOWN -> {
                    val result = MarkdownExport.render(doc, assets, writeMedia = options.markdownAsZip)
                    if (options.markdownAsZip) {
                        val zip = ZipOutputStream(out)
                        zip.putNextEntry(ZipEntry("${sanitizeFileName(doc.title)}.md"))
                        zip.write(result.markdown.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        for ((name, bytes) in result.media) {
                            zip.putNextEntry(ZipEntry(name))
                            zip.write(bytes)
                            zip.closeEntry()
                        }
                        zip.finish()
                    } else out.write(result.markdown.toByteArray(Charsets.UTF_8))
                }
                ExportFormat.SSNOTE, ExportFormat.SDOCX -> error("unreachable")
            }
            out.flush()
            target
        }
    }
}

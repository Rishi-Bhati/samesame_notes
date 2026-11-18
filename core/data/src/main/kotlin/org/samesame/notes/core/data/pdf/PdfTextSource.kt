package org.samesame.notes.core.data.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.samesame.notes.core.model.pdf.PdfPageText
import java.io.Closeable
import java.io.File

/** One PDF kept open for repeated page extraction (loading a document per page is far too slow). Not thread safe. */
class PdfTextSource(file: File, private val extractor: PdfTextExtractor) : Closeable {
    private val document: PDDocument = PDDocument.load(file)

    val pageCount: Int get() = document.numberOfPages

    fun page(index: Int): PdfPageText = extractor.extractPage(document, index)

    override fun close() {
        runCatching { document.close() }
    }
}

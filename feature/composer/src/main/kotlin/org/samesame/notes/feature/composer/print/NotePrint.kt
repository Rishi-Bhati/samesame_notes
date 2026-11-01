package org.samesame.notes.feature.composer.print

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.OutputStream

/** Print (#60): the note's PDF export goes to the Android print framework through a [PrintDocumentAdapter]. */
object NotePrint {
    /**
     * Starts a print job named [jobName]. [writePdf] writes the PDF export of the note (the repository's export, so the
     * page layout matches "Save as file" -> PDF); it runs when the print service asks for the document.
     */
    fun print(context: Context, jobName: String, writePdf: suspend (OutputStream) -> Unit) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        printManager.print(jobName.ifBlank { "Note" }, PdfPrintAdapter(jobName.ifBlank { "Note" }, writePdf), PrintAttributes.Builder().build())
    }
}

/** Long-running writes go here: the print framework calls [PrintDocumentAdapter.onWrite] on the main thread. */
private val printWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private class PdfPrintAdapter(
    private val name: String,
    private val writePdf: suspend (OutputStream) -> Unit,
) : PrintDocumentAdapter() {
    private var writeJob: Job? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        // The page count is only known once the PDF is written.
        val info = PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        callback.onLayoutFinished(info, newAttributes != oldAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        val out = ParcelFileDescriptor.AutoCloseOutputStream(destination)
        cancellationSignal?.setOnCancelListener { writeJob?.cancel() }
        writeJob = printWriteScope.launch {
            try {
                out.use { writePdf(it) }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: CancellationException) {
                callback.onWriteCancelled()
                throw e
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
}

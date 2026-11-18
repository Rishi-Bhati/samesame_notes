package org.samesame.notes.core.export.render

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAdapterBridge
import android.print.PrintAttributes
import android.print.PrintDocumentInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.samesame.notes.core.model.Limits
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Headless PDF export for Markdown notes using an off-screen WebView and PrintDocumentAdapter.
 * Creates an ISO A4 document with native text wrapping and vector tables.
 */
object MarkdownPdfExport {

    suspend fun write(context: Context, html: String, jobName: String, out: OutputStream) {
        withTimeout(Limits.PDF_RENDER_TIMEOUT_MS) {
            coroutineScope {
                var webView: WebView? = null
                val layoutSignal = CancellationSignal()
                val writeSignal = CancellationSignal()
                var pipe: Array<ParcelFileDescriptor>? = null

                try {
                    // 1. Create and load WebView on Main dispatcher
                    val adapter = withContext(Dispatchers.Main) {
                        val wv = WebView(context).also { webView = it }
                        wv.settings.javaScriptEnabled = false
                        val pageLoaded = CompletableDeferred<Unit>()
                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageLoaded.complete(Unit)
                            }
                            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                                pageLoaded.completeExceptionally(IOException("WebView failed to load HTML: $description"))
                            }
                        }
                        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                        pageLoaded.await()
                        wv.createPrintDocumentAdapter(jobName.ifBlank { "Note" })
                    }

                    // 2. Layout document via PrintDocumentAdapter on Main dispatcher
                    val attributes = PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build()

                    val layoutFinished = CompletableDeferred<PrintDocumentInfo?>()
                    withContext(Dispatchers.Main) {
                        adapter.onLayout(
                            null,
                            attributes,
                            layoutSignal,
                            PrintAdapterBridge.createLayoutCallback(object : PrintAdapterBridge.LayoutHandler {
                                override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                                    layoutFinished.complete(info)
                                }
                                override fun onLayoutFailed(error: CharSequence?) {
                                    layoutFinished.completeExceptionally(IOException("PDF layout failed: $error"))
                                }
                                override fun onLayoutCancelled() {
                                    layoutFinished.completeExceptionally(CancellationException("PDF layout cancelled"))
                                }
                            }),
                            Bundle(),
                        )
                    }
                    layoutFinished.await()

                    // 3. Write PDF to ParcelFileDescriptor pipe and stream to OutputStream
                    val createdPipe = ParcelFileDescriptor.createPipe().also { pipe = it }
                    val readSide = createdPipe[0]
                    val writeSide = createdPipe[1]

                    val streamingJob = async(Dispatchers.IO) {
                        ParcelFileDescriptor.AutoCloseInputStream(readSide).use { input ->
                            input.copyTo(out)
                        }
                    }

                    val writeFinished = CompletableDeferred<Unit>()
                    withContext(Dispatchers.Main) {
                        adapter.onWrite(
                            arrayOf(PageRange.ALL_PAGES),
                            writeSide,
                            writeSignal,
                            PrintAdapterBridge.createWriteCallback(object : PrintAdapterBridge.WriteHandler {
                                override fun onWriteFinished(pages: Array<out PageRange>?) {
                                    writeFinished.complete(Unit)
                                }
                                override fun onWriteFailed(error: CharSequence?) {
                                    writeFinished.completeExceptionally(IOException("PDF write failed: $error"))
                                }
                                override fun onWriteCancelled() {
                                    writeFinished.completeExceptionally(CancellationException("PDF write cancelled"))
                                }
                            }),
                        )
                    }

                    writeFinished.await()
                    streamingJob.await()
                } finally {
                    layoutSignal.cancel()
                    writeSignal.cancel()
                    pipe?.forEach { runCatching { it.close() } }
                    withContext(Dispatchers.Main) {
                        runCatching { webView?.destroy() }
                    }
                }
            }
        }
    }
}

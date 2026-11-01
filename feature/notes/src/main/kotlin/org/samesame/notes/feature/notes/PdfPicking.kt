package org.samesame.notes.feature.notes

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Copies the picked PDFs into the cache (PdfRenderer needs a seekable file) and reads their display names.
 * Files that can't be read are skipped. Call off the main thread; delete the files when done.
 */
internal fun copyPdfsToCache(context: Context, uris: List<Uri>): List<PdfSource> {
    val dir = File(context.cacheDir, "pdf-import").apply { deleteRecursively(); mkdirs() }
    return uris.mapIndexedNotNull { index, uri ->
        runCatching {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            val file = File(dir, "$index.pdf")
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            PdfSource(file, pdfTitle(name))
        }.getOrNull()
    }
}

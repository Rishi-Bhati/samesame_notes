package org.samesame.notes

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import org.samesame.notes.core.data.transfer.NoteFileDetector

/** A file handed to the app by another app, with what [NoteFileDetector] made of it. */
data class IncomingFile(val uri: Uri, val displayName: String?, val mimeType: String?, val verdict: NoteFileDetector.Verdict)

/**
 * Resolves the evidence [NoteFileDetector] needs for a content/file URI. Files providers (Google Files'
 * `com.google.android.apps.nbu.files.provider`) return opaque ids as the last path segment, so the file name only
 * exists as `OpenableColumns.DISPLAY_NAME`; querying it is the difference between importing `Hi.sdocx` and doing nothing.
 */
class IncomingFileResolver(private val contentResolver: ContentResolver) {

    fun resolve(uri: Uri, intentMimeType: String?): IncomingFile {
        val providerMimeType = runCatching { contentResolver.getType(uri) }.getOrNull()
        val mimeType = when {
            intentMimeType.isNullOrBlank() -> providerMimeType
            isGeneric(intentMimeType) -> providerMimeType ?: intentMimeType
            else -> intentMimeType
        }
        val name = queryDisplayName(uri)
        return IncomingFile(uri, name, mimeType, NoteFileDetector.classify(name, mimeType, uri.lastPathSegment))
    }

    companion object {
        /** Only `content://` URIs of other apps' providers are imported (no `file://`, nothing served by this app). */
        fun isForeignContent(uri: Uri, ownPackage: String): Boolean =
            uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority?.let { it == ownPackage || it.startsWith("$ownPackage.") } == false
    }

    private fun isGeneric(mimeType: String): Boolean = mimeType.substringBefore(';').trim().lowercase().let {
        it == "application/octet-stream" || it == "*/*"
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getString(column) else null
            }
        }.getOrNull()
    }
}

package org.samesame.notes.core.files.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.samesame.notes.core.model.files.FileId
import java.io.FileNotFoundException

/**
 * Read-only access to library files for other apps (Share, Open with) and our viewers:
 * `content://<applicationId>.files/f/<fileId>/<displayName>`. Not exported; access comes only from per-intent grants.
 * Only the id resolves the file (the name segment is a label for the receiving app). Ids that aren't UUIDs, unknown
 * ids and files in the Recycle bin are "not found". Writes are refused. The app declares a concrete subclass
 * (:core:data `AppLibraryContentProvider`, authority `${applicationId}.files`, not exported, grantUriPermissions).
 */
abstract class LibraryContentProvider : ContentProvider() {

    /**
     * Where ids are resolved. Called per request, never from [onCreate]: providers are created before the
     * application (and its dependency graph).
     */
    protected abstract fun lookup(): LibraryFileLookup

    override fun onCreate(): Boolean = true

    private fun resolve(uri: Uri): ProvidedFile {
        val id = LibraryProviderContract.fileIdOf(uri) ?: throw FileNotFoundException("Not a library file URI")
        val file = lookup().find(id.value) ?: throw FileNotFoundException("No such file")
        if (!file.blob.isFile) throw FileNotFoundException("File content is missing")
        return file
    }

    override fun getType(uri: Uri): String? = runCatching { resolve(uri).mimeType }.getOrNull()

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val file = runCatching { resolve(uri) }.getOrNull() ?: return null
        val columns = (projection ?: DEFAULT_COLUMNS).filter { it in DEFAULT_COLUMNS }.toTypedArray()
        val row = columns.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> file.displayName
                OpenableColumns.SIZE -> file.sizeBytes
                else -> file.mimeType
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Library files are read-only")
        return ParcelFileDescriptor.open(resolve(uri).blob, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")

    private companion object {
        const val MIME_TYPE_COLUMN = "mime_type"
        val DEFAULT_COLUMNS = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, MIME_TYPE_COLUMN)
    }
}

/** URIs of [LibraryContentProvider]. */
object LibraryProviderContract {
    private const val FILE_SEGMENT = "f"

    fun authority(context: Context): String = context.packageName + ".files"

    fun uriFor(authority: String, id: FileId, displayName: String): Uri = Uri.Builder()
        .scheme("content").authority(authority).appendPath(FILE_SEGMENT).appendPath(id.value).appendPath(displayName).build()

    fun uriFor(context: Context, id: FileId, displayName: String): Uri = uriFor(authority(context), id, displayName)

    /** The id in a provider URI, or null when the URI is not one of ours or the id is not a UUID. */
    fun fileIdOf(uri: Uri): FileId? {
        val segments = uri.pathSegments
        if (segments.size < 2 || segments[0] != FILE_SEGMENT) return null
        return FileId(segments[1].lowercase()).takeIf { it.isWellFormed }
    }
}

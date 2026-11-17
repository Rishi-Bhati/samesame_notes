package org.samesame.notes.feature.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.samesame.notes.core.files.UploadChild
import org.samesame.notes.core.files.UploadTreeSource

/**
 * [UploadTreeSource] over a `ACTION_OPEN_DOCUMENT_TREE` URI. Queries run on the caller's `Dispatchers.IO` thread;
 * cancellation is checked while paging through children. No file bytes are read here (only the tree structure).
 */
class DocumentsTreeSource(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : UploadTreeSource<String> {
    override suspend fun children(parentId: String?): List<UploadChild<String>> {
        currentCoroutineContext().ensureActive()
        val documentId = parentId ?: DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = ArrayList<UploadChild<String>>()
        resolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(columns[0])
            val nameIndex = cursor.getColumnIndex(columns[1])
            val mimeIndex = cursor.getColumnIndex(columns[2])
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (idIndex < 0) break
                val id = cursor.getString(idIndex) ?: continue
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) ?: id else id
                val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null
                out += UploadChild(id, name, mime == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return out
    }

    /** Opens one document's content; the caller closes the stream (the import pipeline does). */
    fun open(fileId: String) =
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, fileId))
            ?: throw java.io.FileNotFoundException("Can't open $fileId")
}

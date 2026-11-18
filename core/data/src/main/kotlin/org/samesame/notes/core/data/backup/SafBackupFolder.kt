package org.samesame.notes.core.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import java.io.OutputStream

/** [BackupFolder] backed by a SAF tree URI. */
class SafBackupFolder(
    private val context: Context,
    private val treeUri: Uri,
    override val displayName: String? = null,
) : BackupFolder {
    private val resolver: ContentResolver get() = context.contentResolver

    private data class Doc(
        val uri: Uri,
        val documentId: String,
        val name: String,
        val mimeType: String?,
        val size: Long,
    ) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun root(): Doc? {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return queryDoc(uri, documentId)
    }

    override fun exists(): Boolean = root()?.isDirectory == true

    override fun canWrite(): Boolean = runCatching {
        val r = root() ?: return@runCatching false
        val flags = queryFlags(r.uri) ?: return@runCatching true
        (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0 ||
            (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
    }.getOrDefault(false)

    override fun list(path: String): List<BackupEntry> {
        val dir = find(path) ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return children(dir).map { f ->
            object : BackupEntry {
                override val name: String = f.name
                override val isDirectory: Boolean = f.isDirectory
                override val size: Long = f.size
            }
        }
    }

    override fun isDirectory(path: String): Boolean = find(path)?.isDirectory == true

    override fun exists(path: String): Boolean = find(path) != null

    override fun createDirectory(path: String): Boolean {
        if (path.isBlank()) return exists()
        val parts = segments(path)
        var cur = root() ?: return false
        for (part in parts) {
            val next = childByName(cur, part)
            cur = when {
                next == null -> {
                    val createdUri = runCatching {
                        DocumentsContract.createDocument(resolver, cur.uri, DocumentsContract.Document.MIME_TYPE_DIR, part)
                    }.getOrNull() ?: return false
                    val docId = runCatching { DocumentsContract.getDocumentId(createdUri) }.getOrNull()
                    Doc(createdUri, docId.orEmpty(), part, DocumentsContract.Document.MIME_TYPE_DIR, 0L)
                }
                next.isDirectory -> next
                else -> return false
            }
        }
        return true
    }

    override fun openOutput(path: String, mimeType: String): OutputStream {
        val parts = segments(path)
        require(parts.isNotEmpty()) { "Empty path" }
        val parentPath = parts.dropLast(1).joinToString("/")
        if (parentPath.isNotEmpty()) check(createDirectory(parentPath)) { "Could not create parent: $parentPath" }
        val parent = (if (parentPath.isEmpty()) root() else find(parentPath))
            ?: throw java.io.IOException("Could not find parent: $path")
        val name = parts.last()
        val existing = childByName(parent, name)?.takeIf { !it.isDirectory }
        val docUri = existing?.uri ?: runCatching {
            DocumentsContract.createDocument(resolver, parent.uri, mimeType, name)
        }.getOrNull() ?: throw java.io.IOException("Could not create file: $path")
        return resolver.openOutputStream(docUri, "wt") ?: throw java.io.IOException("Could not open output: $path")
    }

    override fun openInput(path: String): InputStream {
        val doc = find(path) ?: throw java.io.FileNotFoundException(path)
        return resolver.openInputStream(doc.uri) ?: throw java.io.FileNotFoundException(path)
    }

    override fun rename(from: String, to: String): Boolean {
        if (from == to) return true
        val fromParts = segments(from)
        val toParts = segments(to)
        if (fromParts.isEmpty() || toParts.isEmpty()) return false
        val sameParent = fromParts.dropLast(1) == toParts.dropLast(1)
        val src = find(from) ?: return false
        if (sameParent) {
            return runCatching { DocumentsContract.renameDocument(resolver, src.uri, toParts.last()) != null }.getOrDefault(false)
        }
        // Cross-directory move: copy then delete (SAF has no portable move).
        val ok = copyRec(src, to)
        if (ok) runCatching { DocumentsContract.deleteDocument(resolver, src.uri) }
        return ok
    }

    override fun delete(path: String): Boolean {
        val doc = find(path) ?: return true
        return runCatching { DocumentsContract.deleteDocument(resolver, doc.uri) }.getOrDefault(false)
    }

    private fun copyRec(src: Doc, dstPath: String): Boolean {
        return if (src.isDirectory) {
            if (!createDirectory(dstPath)) return false
            children(src).all { child -> copyRec(child, "$dstPath/${child.name}") }
        } else {
            try {
                openOutput(dstPath, src.mimeType ?: "application/octet-stream").use { out ->
                    resolver.openInputStream(src.uri)?.use { it.copyTo(out) } ?: return false
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun find(path: String): Doc? {
        if (path.isBlank()) return root()
        var cur = root() ?: return null
        for (part in segments(path)) {
            cur = childByName(cur, part) ?: return null
        }
        return cur
    }

    private fun childByName(parent: Doc, name: String): Doc? = children(parent).firstOrNull { it.name == name }

    private fun children(parent: Doc): List<Doc> {
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.documentId.ifBlank { DocumentsContract.getDocumentId(parent.uri) })
        }.getOrNull() ?: return emptyList()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = mutableListOf<Doc>()
        runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (c.moveToNext()) {
                    val id = if (idIdx >= 0) c.getString(idIdx) else continue
                    val name = if (nameIdx >= 0) c.getString(nameIdx).orEmpty() else continue
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null
                    val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    out += Doc(uri, id, name, mime, size)
                }
            }
        }
        return out
    }

    private fun queryDoc(uri: Uri, documentId: String): Doc? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return runCatching {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val name = if (nameIdx >= 0) c.getString(nameIdx).orEmpty() else ""
                val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                Doc(uri, documentId, name, mime, size)
            }
        }.getOrNull()
    }

    private fun queryFlags(uri: Uri): Int? {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_FLAGS)
        return runCatching {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                if (idx >= 0 && !c.isNull(idx)) c.getInt(idx) else null
            }
        }.getOrNull()
    }

    private fun segments(path: String): List<String> =
        path.trim().trim('/').replace('\\', '/').split('/').filter { it.isNotBlank() }

    companion object {
        fun fromUriString(context: Context, uriString: String): SafBackupFolder? {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
            val name = runCatching {
                val docId = DocumentsContract.getTreeDocumentId(uri)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                context.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            return SafBackupFolder(context, uri, name)
        }
    }
}

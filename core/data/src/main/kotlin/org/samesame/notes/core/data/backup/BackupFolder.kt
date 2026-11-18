package org.samesame.notes.core.data.backup

import java.io.InputStream
import java.io.OutputStream

/** A file or directory inside a backup location. Paths use `/` separators relative to the root of this folder. */
interface BackupEntry {
    val name: String
    val isDirectory: Boolean
    val size: Long
}

/**
 * Abstraction over the chosen backup location so the engine works with SAF (`DocumentFile`/`DocumentsContract`) and
 * with plain [java.io.File] in tests. All [path] values are relative to this root and use `/` as separator.
 */
interface BackupFolder {
    /** Name shown to the user (folder display name), or null when unknown. */
    val displayName: String?

    fun exists(): Boolean
    fun canWrite(): Boolean

    fun list(path: String = ""): List<BackupEntry>
    fun isDirectory(path: String): Boolean
    fun exists(path: String): Boolean

    fun createDirectory(path: String): Boolean
    /** Creates parent directories as needed and returns a stream that overwrites the file at [path]. */
    fun openOutput(path: String, mimeType: String = "application/octet-stream"): OutputStream
    fun openInput(path: String): InputStream

    fun rename(from: String, to: String): Boolean
    fun delete(path: String): Boolean
}

/** Error reported when the chosen backup folder cannot be used. */
class BackupFolderUnavailableException(message: String? = null, cause: Throwable? = null) :
    java.io.IOException(message ?: "Folder not available — choose it again", cause)

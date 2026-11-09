package org.samesame.notes.core.files.provider

import java.io.File

/** What the provider serves for one id: the stored copy plus its label. */
data class ProvidedFile(val displayName: String, val mimeType: String, val sizeBytes: Long, val blob: File)

/**
 * Resolves provider ids. Bound in :core:data (database + blob store). Returns null for unknown ids and for files in the
 * Recycle bin. May block; the provider calls it on binder threads (or the caller's thread for in-process callers).
 */
fun interface LibraryFileLookup {
    fun find(fileId: String): ProvidedFile?
}

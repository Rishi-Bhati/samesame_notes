package org.samesame.notes.feature.composer.files

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.LibraryFile

/** What the editor needs from the Files library (backed by `FilesRepository` in [org.samesame.notes.feature.composer.ComposerViewModel]). */
interface NoteFileLinks {
    /** The library rows of [ids], trashed ones included; unknown ids are left out. */
    suspend fun resolve(ids: List<FileId>): Map<FileId, LibraryFile>

    /** Every live file, newest first (the inline `///` picker). */
    fun observeAllFiles(): Flow<List<LibraryFile>>

    /** Copies the picked documents into the Files library and returns the new ids (files that failed are left out). */
    suspend fun import(uris: List<Uri>): List<FileId>

    /** Read-only content URI of a live file (grant per intent). */
    fun contentUri(file: LibraryFile): Uri

    /** The file's bytes, or null when it can't be read (trashed, or its content is missing on this device). */
    suspend fun readBytes(file: LibraryFile, maxBytes: Long): ByteArray?

    /** Takes [id] out of the Files Recycle bin. */
    suspend fun restore(id: FileId)
}

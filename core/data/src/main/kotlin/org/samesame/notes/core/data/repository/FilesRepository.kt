package org.samesame.notes.core.data.repository

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import org.samesame.notes.core.model.files.DuplicatePolicy
import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.FileFolder
import org.samesame.notes.core.model.files.FileFolderId
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.FileSort
import org.samesame.notes.core.model.files.FileUsage
import org.samesame.notes.core.model.files.FilesSummary
import org.samesame.notes.core.model.files.FilesTrash
import org.samesame.notes.core.model.files.ImportResult
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.core.model.files.PendingImport
import java.io.InputStream

/**
 * The Files library (docs/architecture/files-feature.md): local copies of any file, in user folders, with a Recycle bin.
 * Frozen for phase F-2 (docs/checkpoints/rel020-f1.md): add members, don't change these.
 *
 * Folder rules: names go through `FileNames.sanitizeFolderName` (at most `Limits.MAX_FILE_FOLDER_NAME_LENGTH`), depth is
 * capped by `Limits.MAX_FOLDER_DEPTH`; invalid input throws [IllegalArgumentException]. Items in the Recycle bin are
 * purged after `Limits.TRASH_RETENTION_DAYS`.
 */
interface FilesRepository {
    // ---- Folders ----

    /** Every folder outside the Recycle bin, in tree order (depth-first, siblings by sortIndex then name). */
    fun observeFolders(): Flow<List<FileFolder>>

    /** @throws IllegalArgumentException on an empty name, an unknown/trashed parent or a too-deep tree. */
    suspend fun createFolder(name: String, parentId: FileFolderId?, color: Long? = null): FileFolderId
    suspend fun renameFolder(id: FileFolderId, name: String)
    suspend fun setFolderColor(id: FileFolderId, color: Long?)

    /** Moves a folder (with its subtree) under [newParentId] (null = Files root). Rejects cycles and too-deep trees. */
    suspend fun moveFolder(id: FileFolderId, newParentId: FileFolderId?)

    /** The folder chain from the top level down to [id] (inclusive); empty for an unknown id. */
    suspend fun folderPath(id: FileFolderId): List<FileFolder>

    // ---- Files ----

    /** Files directly in [folderId] (null = Files root), outside the Recycle bin, ordered by [sort]. */
    fun observeFiles(folderId: FileFolderId?, sort: FileSort): Flow<List<LibraryFile>>

    /** Every file outside the Recycle bin, optionally only [categories] (e.g. the "Insert from Files" picker). */
    fun observeAllFiles(sort: FileSort, categories: Set<FileCategory>? = null): Flow<List<LibraryFile>>

    /** One file (also when trashed); null once it is gone. */
    fun observeFile(id: FileId): Flow<LibraryFile?>
    suspend fun getFile(id: FileId): LibraryFile?

    /** The files that exist among [ids] (trashed included), in no particular order. */
    suspend fun getFiles(ids: List<FileId>): List<LibraryFile>

    /** Name search (prefix match per word) over files outside the Recycle bin. Blank query = empty list. */
    fun search(query: String): Flow<List<LibraryFile>>

    /** Counts and sizes for the subtitle, the drawer badge and the Storage page. */
    fun observeSummary(): Flow<FilesSummary>

    // ---- Import (copies the content; nothing is linked to the source) ----

    /**
     * Copies [uri] (a SAF document or a share-sheet stream) into Files. The name and size come from the provider's
     * `OpenableColumns` unless [displayName] is given. Call while the URI grant is valid (the copy is not deferred).
     * Cancelling the coroutine stops the copy and leaves nothing behind.
     *
     * @param onProgress copied bytes and the total when known.
     * @throws org.samesame.notes.core.model.files.LibraryFileTooLargeException over `Limits.MAX_LIBRARY_FILE_BYTES`
     * @throws org.samesame.notes.core.model.files.NotEnoughSpaceException under the free-space reserve
     * @throws java.io.IOException when the source can't be read
     * @throws IllegalArgumentException when [folderId] is unknown or in the Recycle bin
     */
    suspend fun importFromUri(
        uri: Uri,
        folderId: FileFolderId?,
        displayName: String? = null,
        sourceApp: String? = null,
        onDuplicate: DuplicatePolicy = DuplicatePolicy.Ask,
        onProgress: (copied: Long, total: Long?) -> Unit = { _, _ -> },
    ): ImportResult

    /** As [importFromUri], from a stream [open]ed by the caller (closed here). [declaredMime] is a hint only. */
    suspend fun importStream(
        open: () -> InputStream,
        displayName: String,
        folderId: FileFolderId?,
        declaredMime: String? = null,
        declaredSize: Long? = null,
        sourceApp: String? = null,
        onDuplicate: DuplicatePolicy = DuplicatePolicy.Ask,
        onProgress: (copied: Long, total: Long?) -> Unit = { _, _ -> },
    ): ImportResult

    /**
     * Answers "Already in this folder": [keepBoth] adds the file (named `name (2).ext`, ...) and returns it; otherwise
     * nothing happens and null is returned.
     */
    suspend fun resolveDuplicate(pending: PendingImport, keepBoth: Boolean): LibraryFile?

    // ---- Edit ----

    /** Renames (sanitised; keeps the old extension when [name] has none). Returns the stored name. */
    suspend fun rename(id: FileId, name: String): String
    suspend fun moveFiles(ids: List<FileId>, folderId: FileFolderId?)
    suspend fun setFavorite(ids: List<FileId>, favorite: Boolean)

    /** Records lazily read metadata (viewers, thumbnails). Null leaves a value unchanged. */
    suspend fun updateMetadata(id: FileId, width: Int? = null, height: Int? = null, durationMs: Long? = null, pageCount: Int? = null)

    // ---- Recycle bin ----

    /** The Files Recycle bin, newest first. */
    fun observeTrash(): Flow<FilesTrash>

    /** Moves files and folder subtrees (with every file inside) to the Recycle bin, stamped with one timestamp. */
    suspend fun trash(fileIds: List<FileId>, folderIds: List<FileFolderId> = emptyList())

    /** Restores; items whose folder is trashed or gone go to the Files root. */
    suspend fun restore(fileIds: List<FileId>, folderIds: List<FileFolderId> = emptyList())

    /** Removes for good (rows, and stored content no other file uses). */
    suspend fun deletePermanently(fileIds: List<FileId>, folderIds: List<FileFolderId> = emptyList())
    suspend fun emptyTrash()

    /** Permanently deletes items that have been in the Recycle bin longer than the retention period. */
    suspend fun purgeExpiredTrash()

    // ---- Sharing, links, backlinks ----

    /**
     * A read-only `content://<applicationId>.files/f/<id>/<name>` URI for Share / Open with / viewers. Grant it with
     * `FLAG_GRANT_READ_URI_PERMISSION`. The link text itself is `FileLinks.format(file)` (core:model).
     */
    fun contentUri(file: LibraryFile): Uri

    /** The notes that use the file (links or images shown in place), including notes in the Recycle bin. */
    fun observeUsage(id: FileId): Flow<List<FileUsage>>

    /** Distinct notes using any of [ids] (the delete warning "Used in N notes"). */
    suspend fun usageCount(ids: List<FileId>): Int
}

package org.samesame.notes.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.samesame.notes.core.database.entity.FileFolderEntity
import org.samesame.notes.core.database.entity.FileRefEntity
import org.samesame.notes.core.database.entity.FileUsageRow
import org.samesame.notes.core.database.entity.FolderFileCount
import org.samesame.notes.core.database.entity.LibraryFileEntity
import org.samesame.notes.core.database.entity.LibrarySummaryRow

/** The Files library tables (schema v3): folders, files, backlinks and name search. */
@Dao
interface LibraryDao {
    // ---- folders ----

    @Query("SELECT * FROM file_folders WHERE trashed_at IS NULL")
    fun observeLiveFolders(): Flow<List<FileFolderEntity>>

    @Query("SELECT * FROM file_folders")
    fun observeAllFolders(): Flow<List<FileFolderEntity>>

    @Query("SELECT * FROM file_folders")
    suspend fun allFolders(): List<FileFolderEntity>

    @Query("SELECT * FROM file_folders WHERE id = :id")
    suspend fun folder(id: String): FileFolderEntity?

    @Insert
    suspend fun insertFolder(folder: FileFolderEntity)

    @Query("UPDATE file_folders SET name = :name, modified_at = :now WHERE id = :id")
    suspend fun renameFolder(id: String, name: String, now: Long)

    @Query("UPDATE file_folders SET color = :color, modified_at = :now WHERE id = :id")
    suspend fun setFolderColor(id: String, color: Long?, now: Long)

    @Query("UPDATE file_folders SET parent_id = :parentId, sort_index = :sortIndex, modified_at = :now WHERE id = :id")
    suspend fun setFolderParent(id: String, parentId: String?, sortIndex: Long, now: Long)

    @Query("UPDATE file_folders SET trashed_at = :trashedAt WHERE id IN (:ids)")
    suspend fun setFoldersTrashed(ids: List<String>, trashedAt: Long?)

    @Query("UPDATE file_folders SET parent_id = NULL WHERE id IN (:ids)")
    suspend fun moveFoldersToRoot(ids: List<String>)

    @Query("DELETE FROM file_folders WHERE id IN (:ids)")
    suspend fun deleteFolders(ids: List<String>)

    @Query("SELECT COALESCE(MAX(sort_index), 0) FROM file_folders WHERE parent_id IS :parentId")
    suspend fun maxFolderSortIndex(parentId: String?): Long

    @Query("SELECT folder_id, COUNT(*) AS count FROM library_files WHERE trashed_at IS NULL AND folder_id IS NOT NULL GROUP BY folder_id")
    fun observeFolderFileCounts(): Flow<List<FolderFileCount>>

    // ---- files ----

    @Query("SELECT * FROM library_files WHERE folder_id IS :folderId AND trashed_at IS NULL")
    fun observeLiveFilesIn(folderId: String?): Flow<List<LibraryFileEntity>>

    @Query("SELECT * FROM library_files WHERE trashed_at IS NULL")
    fun observeLiveFiles(): Flow<List<LibraryFileEntity>>

    @Query("SELECT * FROM library_files WHERE trashed_at IS NOT NULL ORDER BY trashed_at DESC")
    fun observeTrashedFiles(): Flow<List<LibraryFileEntity>>

    @Query("SELECT * FROM library_files WHERE id = :id")
    fun observeFile(id: String): Flow<LibraryFileEntity?>

    @Query("SELECT * FROM library_files WHERE id = :id")
    suspend fun file(id: String): LibraryFileEntity?

    @Query("SELECT * FROM library_files WHERE id IN (:ids)")
    suspend fun files(ids: List<String>): List<LibraryFileEntity>

    @Query("SELECT * FROM library_files WHERE folder_id IS :folderId AND sha256 = :sha256 AND trashed_at IS NULL LIMIT 1")
    suspend fun liveDuplicate(folderId: String?, sha256: String): LibraryFileEntity?

    @Query("SELECT display_name FROM library_files WHERE folder_id IS :folderId AND trashed_at IS NULL")
    suspend fun liveNamesIn(folderId: String?): List<String>

    @Query("SELECT COALESCE(MAX(sort_index), 0) FROM library_files WHERE folder_id IS :folderId")
    suspend fun maxFileSortIndex(folderId: String?): Long

    @Insert
    suspend fun insertFile(file: LibraryFileEntity)

    @Query("UPDATE library_files SET display_name = :name, ext = :ext, modified_at = :now WHERE id = :id")
    suspend fun renameFile(id: String, name: String, ext: String, now: Long)

    @Query("UPDATE library_files SET folder_id = :folderId, modified_at = :now WHERE id IN (:ids)")
    suspend fun moveFiles(ids: List<String>, folderId: String?, now: Long)

    @Query("UPDATE library_files SET is_favorite = :favorite WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<String>, favorite: Boolean)

    @Query(
        """
        UPDATE library_files SET width = COALESCE(:width, width), height = COALESCE(:height, height),
            duration_ms = COALESCE(:durationMs, duration_ms), page_count = COALESCE(:pageCount, page_count)
        WHERE id = :id
        """,
    )
    suspend fun updateMetadata(id: String, width: Int?, height: Int?, durationMs: Long?, pageCount: Int?)

    @Query("UPDATE library_files SET trashed_at = :trashedAt WHERE id IN (:ids) AND trashed_at IS NULL")
    suspend fun trashLiveFiles(ids: List<String>, trashedAt: Long)

    @Query("UPDATE library_files SET trashed_at = :trashedAt WHERE folder_id IN (:folderIds) AND trashed_at IS NULL")
    suspend fun trashLiveFilesIn(folderIds: List<String>, trashedAt: Long)

    @Query("UPDATE library_files SET trashed_at = NULL WHERE id IN (:ids)")
    suspend fun restoreFiles(ids: List<String>)

    @Query("UPDATE library_files SET trashed_at = NULL WHERE folder_id IN (:folderIds) AND trashed_at = :stamp")
    suspend fun restoreFilesIn(folderIds: List<String>, stamp: Long)

    /** Restored files whose folder is trashed or gone go to the root. */
    @Query(
        """
        UPDATE library_files SET folder_id = NULL
        WHERE id IN (:ids) AND folder_id IS NOT NULL
          AND folder_id NOT IN (SELECT id FROM file_folders WHERE trashed_at IS NULL)
        """,
    )
    suspend fun unparentFilesInDeadFolders(ids: List<String>)

    @Query("SELECT id FROM library_files WHERE folder_id IN (:folderIds)")
    suspend fun fileIdsIn(folderIds: List<String>): List<String>

    @Query("SELECT id FROM library_files WHERE trashed_at IS NOT NULL AND trashed_at < :cutoff")
    suspend fun trashedFileIdsBefore(cutoff: Long): List<String>

    @Query("SELECT id FROM file_folders WHERE trashed_at IS NOT NULL AND trashed_at < :cutoff")
    suspend fun trashedFolderIdsBefore(cutoff: Long): List<String>

    @Query("SELECT id FROM library_files WHERE trashed_at IS NOT NULL")
    suspend fun trashedFileIds(): List<String>

    @Query("SELECT id FROM file_folders WHERE trashed_at IS NOT NULL")
    suspend fun trashedFolderIds(): List<String>

    @Query("SELECT sha256 FROM library_files WHERE id IN (:ids)")
    suspend fun shasOf(ids: List<String>): List<String>

    @Query("DELETE FROM library_files WHERE id IN (:ids)")
    suspend fun deleteFiles(ids: List<String>)

    @Query("SELECT COUNT(*) FROM library_files WHERE sha256 = :sha256")
    suspend fun countBySha(sha256: String): Int

    @Query("SELECT DISTINCT sha256 FROM library_files")
    suspend fun allShas(): List<String>

    @Query(
        """
        SELECT library_files.* FROM library_files
        JOIN library_files_fts ON library_files.rowid = library_files_fts.rowid
        WHERE library_files_fts MATCH :ftsQuery AND library_files.trashed_at IS NULL
        """,
    )
    fun search(ftsQuery: String): Flow<List<LibraryFileEntity>>

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN trashed_at IS NULL THEN 1 ELSE 0 END), 0) AS file_count,
            COALESCE(SUM(CASE WHEN trashed_at IS NULL THEN size_bytes ELSE 0 END), 0) AS total_bytes,
            COALESCE(SUM(CASE WHEN trashed_at IS NOT NULL THEN 1 ELSE 0 END), 0) AS trashed_count,
            COALESCE(SUM(CASE WHEN trashed_at IS NOT NULL THEN size_bytes ELSE 0 END), 0) AS trashed_bytes
        FROM library_files
        """,
    )
    fun observeSummary(): Flow<LibrarySummaryRow>

    // ---- backlinks ----

    @Query("DELETE FROM file_refs WHERE note_id = :noteId")
    suspend fun deleteRefsOf(noteId: String)

    @Insert
    suspend fun insertRefs(refs: List<FileRefEntity>)

    /** Replaces a note's backlinks with [refs] (all for [noteId]). */
    @Transaction
    suspend fun replaceRefs(noteId: String, refs: List<FileRefEntity>) {
        deleteRefsOf(noteId)
        if (refs.isNotEmpty()) insertRefs(refs)
    }

    @Query(
        """
        SELECT notes.id AS note_id, notes.title, notes.is_locked, notes.trashed_at, file_refs.kind
        FROM file_refs JOIN notes ON notes.id = file_refs.note_id
        WHERE file_refs.file_id = :fileId
        """,
    )
    fun observeUsage(fileId: String): Flow<List<FileUsageRow>>

    @Query("SELECT COUNT(DISTINCT note_id) FROM file_refs WHERE file_id IN (:fileIds)")
    suspend fun usageCount(fileIds: List<String>): Int

    // ---- multi-step changes ----

    /** Trashes [fileIds] and the subtrees [folderSubtree] (every folder and file inside), all stamped [now]. */
    @Transaction
    suspend fun trash(fileIds: List<String>, folderSubtree: List<String>, now: Long) {
        if (folderSubtree.isNotEmpty()) {
            setLiveFoldersTrashed(folderSubtree, now)
            trashLiveFilesIn(folderSubtree, now)
        }
        if (fileIds.isNotEmpty()) trashLiveFiles(fileIds, now)
    }

    @Query("UPDATE file_folders SET trashed_at = :trashedAt WHERE id IN (:ids) AND trashed_at IS NULL")
    suspend fun setLiveFoldersTrashed(ids: List<String>, trashedAt: Long)

    @Query("UPDATE file_folders SET trashed_at = NULL WHERE id IN (:ids) AND trashed_at = :stamp")
    suspend fun restoreFoldersStamped(ids: List<String>, stamp: Long)

    /** Folders among [ids] whose parent is not live (trashed or gone). */
    @Query(
        """
        SELECT id FROM file_folders
        WHERE id IN (:ids) AND parent_id IS NOT NULL
          AND parent_id NOT IN (SELECT id FROM file_folders WHERE trashed_at IS NULL)
        """,
    )
    suspend fun foldersWithDeadParent(ids: List<String>): List<String>

    /**
     * Restores one trashed folder subtree: the folders and files that were trashed with the root (same stamp). Items
     * trashed separately before stay in the Recycle bin. The root goes to the top level when its parent is not live.
     */
    @Transaction
    suspend fun restoreFolderSubtree(rootId: String, subtree: List<String>, stamp: Long) {
        restoreFoldersStamped(subtree, stamp)
        restoreFilesIn(subtree, stamp)
        val dead = foldersWithDeadParent(listOf(rootId))
        if (dead.isNotEmpty()) moveFoldersToRoot(dead)
    }

    @Transaction
    suspend fun restoreFilesToLiveFolders(ids: List<String>) {
        restoreFiles(ids)
        unparentFilesInDeadFolders(ids)
    }

    /** Deletes the rows; returns the hashes they used (the caller deletes blobs no other row uses). */
    @Transaction
    suspend fun deleteForever(fileIds: List<String>, folderSubtree: List<String>): List<String> {
        val all = (fileIds + if (folderSubtree.isEmpty()) emptyList() else fileIdsIn(folderSubtree)).distinct()
        val shas = all.chunked(CHUNK).flatMap { shasOf(it) }.distinct()
        all.chunked(CHUNK).forEach { deleteFiles(it) }
        folderSubtree.chunked(CHUNK).forEach { deleteFolders(it) }
        return shas
    }
}

/** Stays below SQLite's bound-parameter limit. */
private const val CHUNK = 500

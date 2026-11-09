package org.samesame.notes.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.samesame.notes.core.database.entity.NoteEntity

/** Drawer badges: every note outside the trash, and the ones at the folder root. */
data class NoteCounts(val allCount: Int, val rootCount: Int)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE trashed_at IS NULL")
    fun observeAll(): Flow<List<NoteEntity>>

    /** Counts only: the drawer badges must not fetch and map the whole catalog. */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM notes WHERE trashed_at IS NULL) AS allCount,
               (SELECT COUNT(*) FROM notes WHERE trashed_at IS NULL AND folder_id IS NULL) AS rootCount
        """,
    )
    fun observeCounts(): Flow<NoteCounts>

    @Query("SELECT * FROM notes WHERE folder_id IS NULL AND trashed_at IS NULL")
    fun observeUncategorized(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE is_favorite = 1 AND trashed_at IS NULL")
    fun observeFavorites(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE is_locked = 1 AND trashed_at IS NULL")
    fun observeLocked(): Flow<List<NoteEntity>>

    /** The ids among [ids] whose catalog row is locked (trashed or not). */
    @Query("SELECT id FROM notes WHERE is_locked = 1 AND id IN (:ids)")
    suspend fun lockedAmong(ids: List<String>): List<String>

    @Query("SELECT * FROM notes WHERE trashed_at IS NOT NULL ORDER BY trashed_at DESC")
    fun observeTrash(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE folder_id = :folderId AND trashed_at IS NULL")
    fun observeInFolder(folderId: String): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT * FROM notes
        WHERE trashed_at IS NULL
          AND folder_id IN (SELECT descendant FROM folder_closure WHERE ancestor = :folderId)
        """,
    )
    fun observeInFolderTree(folderId: String): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT notes.* FROM notes
        JOIN note_tags ON note_tags.note_id = notes.id
        WHERE note_tags.tag = :normalizedTag AND notes.trashed_at IS NULL
        """,
    )
    fun observeWithTag(normalizedTag: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Query("UPDATE notes SET title = :title, modified_at = :now, is_dirty = 1 WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, now: Long)

    /** Restores a locked note's visible catalog title without bumping its modified time (used when it is first opened). */
    @Query("UPDATE notes SET title = :title WHERE id = :id")
    suspend fun updateTitleOnly(id: String, title: String)

    @Query(
        "UPDATE notes SET preview_text = :previewText, modified_at = :now, is_dirty = 1 WHERE id = :id",
    )
    suspend fun updatePreview(id: String, previewText: String, now: Long)

    /** Locking keeps the title visible in the catalog but drops the preview text; unlocking restores both. */
    @Query("UPDATE notes SET is_locked = :locked, title = :title, preview_text = :preview, is_dirty = 1 WHERE id = :id")
    suspend fun setLocked(id: String, locked: Boolean, title: String, preview: String)

    @Query("UPDATE notes SET last_opened_at = :now WHERE id = :id")
    suspend fun markOpened(id: String, now: Long)

    @Query("UPDATE notes SET is_favorite = :favorite, is_dirty = 1 WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<String>, favorite: Boolean)

    @Query("UPDATE notes SET folder_id = :folderId, is_dirty = 1 WHERE id IN (:ids)")
    suspend fun moveToFolder(ids: List<String>, folderId: String?)

    /** Pass a timestamp to move to Trash, or null to restore. */
    @Query("UPDATE notes SET trashed_at = :trashedAt, is_dirty = 1 WHERE id IN (:ids)")
    suspend fun setTrashed(ids: List<String>, trashedAt: Long?)

    @Query("SELECT id FROM notes WHERE trashed_at IS NOT NULL AND trashed_at < :cutoff")
    suspend fun trashedBefore(cutoff: Long): List<String>

    @Query("DELETE FROM notes WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)
}

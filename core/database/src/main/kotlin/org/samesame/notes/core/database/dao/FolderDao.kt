package org.samesame.notes.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.samesame.notes.core.database.entity.FolderEntity
import org.samesame.notes.core.database.entity.FolderWithStats

@Dao
interface FolderDao {
    /**
     * Live folders with stats. The card's note count covers the whole subtree (the notes opening the
     * folder lists: `NoteFilter.InFolder` includes subfolders), ignoring trashed notes.
     */
    @Query(
        """
        SELECT f.*,
          (SELECT MAX(c.depth) FROM folder_closure c WHERE c.descendant = f.id) AS depth,
          (SELECT COUNT(*) FROM notes n WHERE n.trashed_at IS NULL AND n.folder_id IN
            (SELECT descendant FROM folder_closure WHERE ancestor = f.id)) AS note_count
        FROM folders f
        WHERE f.trashed_at IS NULL
        ORDER BY f.sort_order, f.name COLLATE NOCASE
        """,
    )
    fun observeWithStats(): Flow<List<FolderWithStats>>

    /** 0 for a root folder, or -1 if [folderId] doesn't exist. */
    @Query("SELECT COALESCE(MAX(depth), -1) FROM folder_closure WHERE descendant = :folderId")
    suspend fun depthOf(folderId: String): Int

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun get(id: String): FolderEntity?

    @Insert
    suspend fun insertFolder(folder: FolderEntity)

    @Query(
        """
        INSERT INTO folder_closure (ancestor, descendant, depth)
        SELECT ancestor, :folderId, depth + 1 FROM folder_closure WHERE descendant = :parentId
        UNION ALL SELECT :folderId, :folderId, 0
        """,
    )
    suspend fun insertClosureRows(folderId: String, parentId: String?)

    /** Inserts the folder and links it under its parent in the closure table. */
    @Transaction
    suspend fun insert(folder: FolderEntity) {
        insertFolder(folder)
        insertClosureRows(folder.id, folder.parentId)
    }

    @Query("UPDATE folders SET name = :name, modified_at = :now, is_dirty = 1 WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE folders SET color = :color, modified_at = :now, is_dirty = 1 WHERE id = :id")
    suspend fun setColor(id: String, color: Long?, now: Long)

    /**
     * Trashed folders with stats for the Recycle bin. The note count covers the whole subtree
     * (everything a folder carries into the Trash is "the items in it").
     */
    @Query(
        """
        SELECT f.*,
          (SELECT MAX(c.depth) FROM folder_closure c WHERE c.descendant = f.id) AS depth,
          (SELECT COUNT(*) FROM notes n WHERE n.folder_id IN
            (SELECT descendant FROM folder_closure WHERE ancestor = f.id)) AS note_count
        FROM folders f
        WHERE f.trashed_at IS NOT NULL
        ORDER BY f.trashed_at DESC, f.name COLLATE NOCASE
        """,
    )
    fun observeTrashedWithStats(): Flow<List<FolderWithStats>>

    /** Every folder in the subtrees rooted at [ids], including [ids] themselves. */
    @Query("SELECT DISTINCT descendant FROM folder_closure WHERE ancestor IN (:ids)")
    suspend fun subtreeIds(ids: List<String>): List<String>

    /** Pass a timestamp to move to Trash, or null to restore. */
    @Query("UPDATE folders SET trashed_at = :trashedAt, is_dirty = 1 WHERE id IN (:ids)")
    suspend fun setTrashed(ids: List<String>, trashedAt: Long?)

    /** The notes inside the given folders (any trash state). */
    @Query("SELECT id FROM notes WHERE folder_id IN (:folderIds)")
    suspend fun noteIdsIn(folderIds: List<String>): List<String>

    @Query("UPDATE notes SET trashed_at = :trashedAt, is_dirty = 1 WHERE folder_id IN (:folderIds)")
    suspend fun setTrashedNotesIn(folderIds: List<String>, trashedAt: Long?)

    @Query("UPDATE folders SET parent_id = NULL, is_dirty = 1 WHERE id IN (:ids)")
    suspend fun moveToRoot(ids: List<String>)

    @Query("DELETE FROM folder_closure WHERE descendant IN (:subtree) AND ancestor NOT IN (:subtree)")
    suspend fun unlinkFromOutsideAncestors(subtree: List<String>)

    @Query("DELETE FROM folders WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT id FROM folders WHERE trashed_at IS NOT NULL AND trashed_at < :cutoff")
    suspend fun trashedBefore(cutoff: Long): List<String>

    /**
     * Folders in [subtree] whose parent is not live (trashed or gone). Run after the subtree came
     * out of the Trash: those folders must be restored to the top level.
     */
    @Query(
        """
        SELECT id FROM folders
        WHERE id IN (:subtree) AND parent_id IS NOT NULL
          AND parent_id NOT IN (SELECT id FROM folders WHERE trashed_at IS NULL)
        """,
    )
    suspend fun orphanedFromLiveParents(subtree: List<String>): List<String>

    /** Moves the folder subtrees to the Trash, their notes included, all stamped [trashedAt]. */
    @Transaction
    suspend fun trashSubtree(rootIds: List<String>, trashedAt: Long) {
        if (rootIds.isEmpty()) return
        val subtree = subtreeIds(rootIds)
        setTrashed(subtree, trashedAt)
        setTrashedNotesIn(subtree, trashedAt)
    }

    /**
     * Brings the folder subtrees back from the Trash. Folders whose original parent is trashed or
     * gone are restored to the top level; the rest keep their place in the tree.
     */
    @Transaction
    suspend fun restoreSubtree(rootIds: List<String>) {
        if (rootIds.isEmpty()) return
        val subtree = subtreeIds(rootIds)
        setTrashed(subtree, null)
        setTrashedNotesIn(subtree, null)
        val orphaned = orphanedFromLiveParents(subtree)
        if (orphaned.isNotEmpty()) {
            val trees = subtreeIds(orphaned)
            moveToRoot(orphaned)
            unlinkFromOutsideAncestors(trees)
        }
    }
}

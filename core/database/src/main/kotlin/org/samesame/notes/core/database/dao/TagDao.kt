package org.samesame.notes.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.samesame.notes.core.database.entity.NoteTagCrossRef
import org.samesame.notes.core.database.entity.TagEntity

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY display_name COLLATE NOCASE")
    fun observeAll(): Flow<List<TagEntity>>

    @Query(
        """
        SELECT tags.display_name FROM tags
        JOIN note_tags ON note_tags.tag = tags.normalized_name
        WHERE note_tags.note_id = :noteId
        ORDER BY tags.display_name COLLATE NOCASE
        """,
    )
    fun observeForNote(noteId: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToNote(ref: NoteTagCrossRef)

    @Query("DELETE FROM note_tags WHERE note_id = :noteId AND tag = :normalizedTag")
    suspend fun removeFromNote(noteId: String, normalizedTag: String)
}

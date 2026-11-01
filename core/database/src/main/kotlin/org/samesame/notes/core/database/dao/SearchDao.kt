package org.samesame.notes.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.samesame.notes.core.database.entity.NoteEntity
import org.samesame.notes.core.database.entity.NoteSearchEntity

@Dao
interface SearchDao {
    /** [ftsQuery] is FTS4 MATCH syntax, e.g. `"meet*" "agenda*"`. */
    @Query(
        """
        SELECT notes.* FROM notes
        JOIN note_search ON note_search.note_id = notes.id
        WHERE note_search MATCH :ftsQuery AND notes.trashed_at IS NULL
        """,
    )
    fun search(ftsQuery: String): Flow<List<NoteEntity>>

    @Query("DELETE FROM note_search WHERE note_id = :noteId")
    suspend fun deleteForNote(noteId: String)

    @Insert
    suspend fun insert(entry: NoteSearchEntity)

    @Transaction
    suspend fun replace(entry: NoteSearchEntity) {
        deleteForNote(entry.noteId)
        insert(entry)
    }
}

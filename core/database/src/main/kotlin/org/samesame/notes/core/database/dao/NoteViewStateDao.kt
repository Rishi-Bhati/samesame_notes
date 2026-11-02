package org.samesame.notes.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import org.samesame.notes.core.database.entity.NoteViewStateEntity

/** The per-note reading position; see [NoteViewStateEntity]. */
@Dao
interface NoteViewStateDao {
    @Query("SELECT * FROM note_view_state WHERE note_id = :noteId")
    suspend fun get(noteId: String): NoteViewStateEntity?

    @Upsert
    suspend fun upsert(state: NoteViewStateEntity)

    @Query("DELETE FROM note_view_state WHERE note_id = :noteId")
    suspend fun delete(noteId: String)
}

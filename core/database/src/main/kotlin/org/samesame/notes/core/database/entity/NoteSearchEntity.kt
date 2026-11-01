package org.samesame.notes.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4

/**
 * Full-text index over everything searchable in a note: title, typed text, recognized handwriting
 * and PDF text. Rebuilt for a note whenever its content is saved or recognition finishes.
 */
@Fts4(notIndexed = ["note_id"])
@Entity(tableName = "note_search")
data class NoteSearchEntity(
    @ColumnInfo(name = "note_id") val noteId: String,
    val title: String,
    val body: String,
    @ColumnInfo(name = "ink_text") val inkText: String,
)

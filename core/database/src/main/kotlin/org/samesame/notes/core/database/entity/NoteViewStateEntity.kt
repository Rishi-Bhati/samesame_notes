package org.samesame.notes.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The reading position of one note: page, offset in that page and zoom. It lives outside the note document on purpose:
 * showing a note and scrolling is not an edit, so it must not change `modified_at`, invalidate the home-card preview or
 * repack (and re-encrypt) the container. Nothing but these three numbers is stored, so a locked note keeps only its
 * position here.
 */
@Entity(tableName = "note_view_state")
data class NoteViewStateEntity(
    @PrimaryKey @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    @ColumnInfo(name = "offset_in_page") val offsetInPage: Float,
    @ColumnInfo(name = "zoom") val zoom: Float,
)

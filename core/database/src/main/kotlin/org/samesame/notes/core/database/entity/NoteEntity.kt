package org.samesame.notes.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.PageMode

/**
 * One row per note. The database is a catalog and index; page content lives in the note's
 * document bundle on disk.
 */
@Entity(
    tableName = "notes",
    indices = [Index("folder_id", "trashed_at"), Index("modified_at")],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "preview_text") val previewText: String,
    @ColumnInfo(name = "folder_id") val folderId: String?,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean,
    @ColumnInfo(name = "is_locked") val isLocked: Boolean,
    @ColumnInfo(name = "page_mode") val pageMode: PageMode,
    @ColumnInfo(name = "background_color") val backgroundColor: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long,
    @ColumnInfo(name = "trashed_at") val trashedAt: Long?,
    /** Set on every local change, cleared by sync once the server has it. */
    @ColumnInfo(name = "is_dirty", defaultValue = "1") val isDirty: Boolean = true,
)

fun NoteEntity.asExternalModel(tags: List<String> = emptyList()) = Note(
    id = NoteId(id),
    title = title,
    previewText = previewText,
    folderId = folderId?.let(::FolderId),
    isFavorite = isFavorite,
    isLocked = isLocked,
    pageMode = pageMode,
    backgroundColor = backgroundColor,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    lastOpenedAt = lastOpenedAt,
    trashedAt = trashedAt,
    tags = tags,
)

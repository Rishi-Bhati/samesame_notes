package org.samesame.notes.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.FileFolderId
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.FileRefKind
import org.samesame.notes.core.model.files.LibraryFile

/** A user folder of the Files library (its own tree, separate from note folders). Added in schema v3. */
@Entity(
    tableName = "file_folders",
    indices = [Index("parent_id", "trashed_at")],
    foreignKeys = [
        ForeignKey(entity = FileFolderEntity::class, parentColumns = ["id"], childColumns = ["parent_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class FileFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    val color: Long?,
    @ColumnInfo(name = "sort_index") val sortIndex: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    @ColumnInfo(name = "trashed_at") val trashedAt: Long?,
)

/**
 * One file in the Files library. The content is the blob `files/library/blobs/<sha256>`, shared by every row with the
 * same hash. A deleted folder never takes files with it silently: `folder_id` falls back to NULL (the Files root).
 */
@Entity(
    tableName = "library_files",
    indices = [Index("folder_id", "trashed_at"), Index("sha256")],
    foreignKeys = [
        ForeignKey(entity = FileFolderEntity::class, parentColumns = ["id"], childColumns = ["folder_id"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class LibraryFileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "folder_id") val folderId: String?,
    @ColumnInfo(name = "display_name") val displayName: String,
    val ext: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    val category: FileCategory,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    val sha256: String,
    val width: Int? = null,
    val height: Int? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "page_count") val pageCount: Int? = null,
    @ColumnInfo(name = "source_app") val sourceApp: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    @ColumnInfo(name = "sort_index") val sortIndex: Long,
    @ColumnInfo(name = "is_favorite", defaultValue = "0") val isFavorite: Boolean = false,
    @ColumnInfo(name = "trashed_at") val trashedAt: Long? = null,
)

/** Name search over [LibraryFileEntity] (external content; Room keeps it in sync with triggers). */
@Fts4(contentEntity = LibraryFileEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "library_files_fts")
data class LibraryFileFtsEntity(
    @ColumnInfo(name = "display_name") val displayName: String,
)

/**
 * Backlinks: how often a note uses a file, by kind. Rebuilt from the document on every save; removed with the note.
 * `file_id` has no foreign key on purpose: a note may link to a file that is gone or not imported yet.
 */
@Entity(
    tableName = "file_refs",
    primaryKeys = ["note_id", "file_id", "kind"],
    indices = [Index("file_id")],
    foreignKeys = [
        ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["note_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class FileRefEntity(
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "file_id") val fileId: String,
    val kind: FileRefKind,
    val count: Int,
)

/** A [FileRefEntity] joined with its note, for "Used in N notes". */
data class FileUsageRow(
    @ColumnInfo(name = "note_id") val noteId: String,
    val title: String,
    @ColumnInfo(name = "is_locked") val isLocked: Boolean,
    @ColumnInfo(name = "trashed_at") val trashedAt: Long?,
    val kind: FileRefKind,
)

data class FolderFileCount(
    @ColumnInfo(name = "folder_id") val folderId: String,
    val count: Int,
)

data class LibrarySummaryRow(
    @ColumnInfo(name = "file_count") val fileCount: Int,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long,
    @ColumnInfo(name = "trashed_count") val trashedCount: Int,
    @ColumnInfo(name = "trashed_bytes") val trashedBytes: Long,
)

fun LibraryFileEntity.asExternalModel() = LibraryFile(
    id = FileId(id),
    folderId = folderId?.let(::FileFolderId),
    displayName = displayName,
    ext = ext,
    mimeType = mimeType,
    category = category,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    width = width,
    height = height,
    durationMs = durationMs,
    pageCount = pageCount,
    sourceApp = sourceApp,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    sortIndex = sortIndex,
    isFavorite = isFavorite,
    trashedAt = trashedAt,
)

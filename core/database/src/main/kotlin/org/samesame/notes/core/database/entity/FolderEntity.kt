package org.samesame.notes.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.samesame.notes.core.model.Folder
import org.samesame.notes.core.model.FolderId

@Entity(tableName = "folders", indices = [Index("parent_id")])
data class FolderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    val name: String,
    val color: Long?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    @ColumnInfo(name = "trashed_at") val trashedAt: Long?,
    @ColumnInfo(name = "is_dirty", defaultValue = "1") val isDirty: Boolean = true,
)

/**
 * Closure table for the folder tree: one row per (ancestor, descendant) pair, including each
 * folder paired with itself at depth 0. Subtree queries become a single indexed lookup.
 */
@Entity(
    tableName = "folder_closure",
    primaryKeys = ["ancestor", "descendant"],
    indices = [Index("descendant")],
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["ancestor"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["descendant"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FolderClosureEntity(
    val ancestor: String,
    val descendant: String,
    val depth: Int,
)

data class FolderWithStats(
    @Embedded val folder: FolderEntity,
    val depth: Int,
    @ColumnInfo(name = "note_count") val noteCount: Int,
)

fun FolderWithStats.asExternalModel() = Folder(
    id = FolderId(folder.id),
    parentId = folder.parentId?.let(::FolderId),
    name = folder.name,
    color = folder.color,
    sortOrder = folder.sortOrder,
    depth = depth,
    noteCount = noteCount,
    trashedAt = folder.trashedAt,
)

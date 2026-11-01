package org.samesame.notes.core.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.samesame.notes.core.data.Clock
import org.samesame.notes.core.database.dao.FolderDao
import org.samesame.notes.core.database.entity.FolderEntity
import org.samesame.notes.core.database.entity.FolderWithStats
import org.samesame.notes.core.database.entity.asExternalModel
import org.samesame.notes.core.model.Folder
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.NoteId
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

interface FolderRepository {
    /** All folders outside the Trash, ordered for display. Use [Folder.parentId] / [Folder.depth] to build the tree. */
    fun observeFolders(): Flow<List<Folder>>

    /** Folders in the Trash (subtrees included), newest first. */
    fun observeTrashedFolders(): Flow<List<Folder>>

    /** @throws IllegalArgumentException if [parentId] is unknown or the tree would get too deep. */
    suspend fun createFolder(name: String, parentId: FolderId?, color: Long? = null): FolderId
    suspend fun rename(id: FolderId, name: String)
    suspend fun setColor(id: FolderId, color: Long?)

    /** Moves the folders, their subfolders and all their notes to the Trash, stamped with one timestamp. */
    suspend fun trash(ids: List<FolderId>)

    /** Brings the subtrees back from the Trash; folders whose parent is trashed or gone go to the top level. */
    suspend fun restore(ids: List<FolderId>)

    /** Removes the folders, their subtrees and the notes' rows and bundle files for good. */
    suspend fun deletePermanently(ids: List<FolderId>)

    /** Permanently deletes folders that have been in Trash longer than the retention period. */
    suspend fun purgeExpiredTrash()
}

internal class DefaultFolderRepository @Inject constructor(
    private val folderDao: FolderDao,
    private val notes: NoteRepository,
    private val clock: Clock,
) : FolderRepository {

    override fun observeFolders(): Flow<List<Folder>> =
        folderDao.observeWithStats().map { it.map(FolderWithStats::asExternalModel) }
            .flowOn(Dispatchers.Default)

    override fun observeTrashedFolders(): Flow<List<Folder>> =
        folderDao.observeTrashedWithStats().map { it.map(FolderWithStats::asExternalModel) }
            .flowOn(Dispatchers.Default)

    override suspend fun createFolder(name: String, parentId: FolderId?, color: Long?): FolderId {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Folder name is empty" }
        if (parentId != null) {
            val parentDepth = folderDao.depthOf(parentId.value)
            require(parentDepth >= 0) { "Unknown parent folder" }
            require(parentDepth + 1 < Limits.MAX_FOLDER_DEPTH) { "Folders can nest ${Limits.MAX_FOLDER_DEPTH} levels deep" }
        }
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        folderDao.insert(
            FolderEntity(
                id = id,
                parentId = parentId?.value,
                name = trimmed,
                color = color,
                sortOrder = 0,
                createdAt = now,
                modifiedAt = now,
                trashedAt = null,
            ),
        )
        return FolderId(id)
    }

    override suspend fun rename(id: FolderId, name: String) = folderDao.rename(id.value, name.trim(), clock.now())

    override suspend fun setColor(id: FolderId, color: Long?) = folderDao.setColor(id.value, color, clock.now())

    override suspend fun trash(ids: List<FolderId>) =
        folderDao.trashSubtree(ids.map(FolderId::value), clock.now())

    override suspend fun restore(ids: List<FolderId>) = folderDao.restoreSubtree(ids.map(FolderId::value))

    override suspend fun deletePermanently(ids: List<FolderId>) {
        val raw = ids.map(FolderId::value)
        if (raw.isEmpty()) return
        val subtree = folderDao.subtreeIds(raw)
        // The notes go through the note permanent-delete path (rows, search entries, bundle files).
        notes.deleteForever(folderDao.noteIdsIn(subtree).map(::NoteId))
        folderDao.delete(subtree) // the closure rows cascade with the folders
    }

    override suspend fun purgeExpiredTrash() {
        val cutoff = clock.now() - TimeUnit.DAYS.toMillis(Limits.TRASH_RETENTION_DAYS.toLong())
        deletePermanently(folderDao.trashedBefore(cutoff).map(::FolderId))
    }
}

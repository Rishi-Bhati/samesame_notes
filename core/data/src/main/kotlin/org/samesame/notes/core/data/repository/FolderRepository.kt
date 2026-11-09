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

    /**
     * Moves the folders, their subfolders and all their notes to the Trash, stamped with one timestamp. Locked notes in
     * the subtrees need [token], as in [NoteRepository.moveToTrash]: otherwise [LockVerificationRequiredException] and
     * nothing moves.
     */
    suspend fun trash(ids: List<FolderId>, token: org.samesame.notes.core.security.LockToken? = null)

    /** Brings the subtrees back from the Trash; folders whose parent is trashed or gone go to the top level. */
    suspend fun restore(ids: List<FolderId>)

    /** Removes the folders, their subtrees and the notes' rows and bundle files for good. Locked notes need [token], as in [trash]. */
    suspend fun deletePermanently(ids: List<FolderId>, token: org.samesame.notes.core.security.LockToken? = null)

    /** The locked notes inside the folders' subtrees (trashed or not), for the "verify your identity" step. */
    suspend fun lockedNoteIdsIn(ids: List<FolderId>): List<NoteId>

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

    override suspend fun trash(ids: List<FolderId>, token: org.samesame.notes.core.security.LockToken?) {
        val raw = ids.map(FolderId::value)
        if (raw.isEmpty()) return
        notes.requireLockVerified(notesIn(raw), token)
        folderDao.trashSubtree(raw, clock.now())
    }

    override suspend fun lockedNoteIdsIn(ids: List<FolderId>): List<NoteId> {
        val raw = ids.map(FolderId::value)
        if (raw.isEmpty()) return emptyList()
        return notes.lockedNoteIds(notesIn(raw))
    }

    /** Every note in the subtrees of [rootIds]. */
    private suspend fun notesIn(rootIds: List<String>): List<NoteId> =
        folderDao.noteIdsIn(folderDao.subtreeIds(rootIds)).map(::NoteId)

    override suspend fun restore(ids: List<FolderId>) = folderDao.restoreSubtree(ids.map(FolderId::value))

    override suspend fun deletePermanently(ids: List<FolderId>, token: org.samesame.notes.core.security.LockToken?) =
        deleteSubtrees(ids.map(FolderId::value), token, purge = false)

    override suspend fun purgeExpiredTrash() {
        val cutoff = clock.now() - TimeUnit.DAYS.toMillis(Limits.TRASH_RETENTION_DAYS.toLong())
        // The 30-day purge is internal, not user-triggered: it needs no credential (owner decision 2026-11-08).
        deleteSubtrees(folderDao.trashedBefore(cutoff), token = null, purge = true)
    }

    private suspend fun deleteSubtrees(raw: List<String>, token: org.samesame.notes.core.security.LockToken?, purge: Boolean) {
        if (raw.isEmpty()) return
        val subtree = folderDao.subtreeIds(raw)
        val noteIds = folderDao.noteIdsIn(subtree).map(::NoteId)
        // The notes go through the note permanent-delete path (rows, search entries, bundle files).
        val unguarded = notes as? DefaultNoteRepository
        if (purge && unguarded != null) unguarded.deleteForeverUnguarded(noteIds) else notes.deleteForever(noteIds, token)
        folderDao.delete(subtree) // the closure rows cascade with the folders
    }
}

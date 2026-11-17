package org.samesame.notes.feature.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.repository.FilesRepository
import org.samesame.notes.core.files.UploadSkip
import org.samesame.notes.core.files.UploadTreeSource
import org.samesame.notes.core.files.planFolderUpload
import org.samesame.notes.core.files.readUploadTree
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.files.DuplicatePolicy
import org.samesame.notes.core.model.files.FileFolder
import org.samesame.notes.core.model.files.FileFolderId
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.ImportResult
import org.samesame.notes.core.model.files.LibraryFileTooLargeException
import org.samesame.notes.core.model.files.NotEnoughSpaceException
import java.io.InputStream

/**
 * Imports a whole document tree through the existing atomic import pipeline. Folders are created with the names from
 * the planner (unique against the user's folders), files are streamed in one by one, and a cancellation rolls back
 * everything this upload created, so no half-imported file or empty folder is left behind.
 */
class FolderUploader(private val repo: FilesRepository) {
    data class Summary(
        val imported: Int,
        val skipped: List<UploadSkip>,
        val failures: List<ImportFailure>,
    ) {
        val skippedCount: Int get() = skipped.size + failures.size
    }

    suspend fun <T> upload(
        source: UploadTreeSource<T>,
        openFile: (T) -> InputStream,
        destination: FileFolderId?,
        onProgress: (ImportProgress) -> Unit,
    ): Summary = withContext(Dispatchers.IO) {
        val plan = planFolderUpload(readUploadTree(source), existingNames(destination))
        val folderIds = HashMap<List<String>, FileFolderId?>()
        folderIds[emptyList()] = destination
        val createdFolders = ArrayList<FileFolderId>()
        val importedFiles = ArrayList<FileId>()
        val failures = ArrayList<ImportFailure>()
        try {
            for (folder in plan.folders) {
                currentCoroutineContext().ensureActive()
                val id = repo.createFolder(folder.name, folderIds[folder.parentPath])
                folderIds[folder.path] = id
                createdFolders += id
            }
            plan.files.forEachIndexed { i, file ->
                currentCoroutineContext().ensureActive()
                val index = i + 1
                onProgress(ImportProgress(index, plan.files.size, file.name))
                try {
                    val result = repo.importStream(
                        open = { openFile(file.value) },
                        displayName = file.name,
                        folderId = folderIds[file.folderPath],
                        onDuplicate = DuplicatePolicy.Skip,
                        onProgress = { copied, total -> onProgress(ImportProgress(index, plan.files.size, file.name, copied, total)) },
                    )
                    if (result is ImportResult.Imported) importedFiles += result.file.id
                } catch (c: CancellationException) {
                    throw c
                } catch (e: LibraryFileTooLargeException) {
                    failures += ImportFailure(file.name, ImportFailureReason.TooLarge)
                } catch (e: NotEnoughSpaceException) {
                    failures += ImportFailure(file.name, ImportFailureReason.NoSpace)
                } catch (e: Exception) {
                    failures += ImportFailure(file.name, ImportFailureReason.Unreadable)
                }
            }
            Summary(importedFiles.size, plan.skipped, failures)
        } catch (c: CancellationException) {
            // deletePermanently removes the folders (and the files in them); a blob no other row uses goes too.
            withContext(NonCancellable) { runCatching { repo.deletePermanently(importedFiles, createdFolders) } }
            throw c
        }
    }

    /** Existing child folder names keyed by their path relative to [destination] (empty = the destination itself). */
    private suspend fun existingNames(destination: FileFolderId?): Map<List<String>, Set<String>> {
        val all = repo.observeFolders().first()
        val byId = all.associateBy { it.id }
        fun chainIds(id: FileFolderId?): List<FileFolderId> {
            if (id == null) return emptyList()
            val out = ArrayList<FileFolderId>()
            var cursor: FileFolder? = byId[id]
            while (cursor != null && out.size <= Limits.MAX_FOLDER_DEPTH) {
                out += cursor.id
                cursor = cursor.parentId?.let(byId::get)
            }
            return out.asReversed()
        }
        val destinationChain = chainIds(destination)
        val out = HashMap<List<String>, MutableSet<String>>()
        for (folder in all) {
            val chain = chainIds(folder.id)
            if (chain.size <= destinationChain.size || chain.take(destinationChain.size) != destinationChain) continue
            val relative = chain.drop(destinationChain.size).map { byId.getValue(it).name }
            out.getOrPut(relative.dropLast(1)) { HashSet() } += relative.last()
        }
        return out
    }
}

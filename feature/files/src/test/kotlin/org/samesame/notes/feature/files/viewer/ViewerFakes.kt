package org.samesame.notes.feature.files.viewer

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.samesame.notes.core.data.repository.FilesRepository
import org.samesame.notes.core.model.files.*
import org.samesame.notes.feature.files.FilesPreferences
import org.samesame.notes.feature.files.FilesViewMode

internal class FakePrefs : FilesPreferences {
    override var sort = FileSort()
    override var viewMode = FilesViewMode.Grid
    override var lastFolderId: String? = null
}

/** An in-memory library: [files] holds live and trashed files alike. */
internal class FakeFilesRepo : FilesRepository {
    val folders = MutableStateFlow<List<FileFolder>>(emptyList())
    val files = MutableStateFlow<List<LibraryFile>>(emptyList())
    val renamed = ArrayList<Pair<FileId, String>>()
    val metadata = ArrayList<Triple<FileId, Int?, Long?>>()

    private fun live(list: List<LibraryFile>) = list.filter { !it.isInTrash }

    override fun observeFolders() = folders
    override suspend fun createFolder(name: String, parentId: FileFolderId?, color: Long?): FileFolderId = error("unused")
    override suspend fun renameFolder(id: FileFolderId, name: String) = Unit
    override suspend fun setFolderColor(id: FileFolderId, color: Long?) = Unit
    override suspend fun moveFolder(id: FileFolderId, newParentId: FileFolderId?) = Unit
    override suspend fun folderPath(id: FileFolderId) = emptyList<FileFolder>()
    override fun observeFiles(folderId: FileFolderId?, sort: FileSort): Flow<List<LibraryFile>> =
        files.map { list -> live(list).filter { it.folderId == folderId } }
    override fun observeAllFiles(sort: FileSort, categories: Set<FileCategory>?): Flow<List<LibraryFile>> =
        files.map { list -> live(list).filter { categories == null || it.category in categories } }
    override fun observeFile(id: FileId) = files.map { l -> l.firstOrNull { it.id == id } }
    override suspend fun getFile(id: FileId) = files.value.firstOrNull { it.id == id }
    override suspend fun getFiles(ids: List<FileId>) = files.value.filter { it.id in ids }
    override fun search(query: String): Flow<List<LibraryFile>> =
        files.map { list -> live(list).filter { it.displayName.contains(query, ignoreCase = true) } }
    override fun observeSummary() = files.map { all ->
        val trashed = all.filter { it.isInTrash }
        FilesSummary(live(all).size, live(all).sumOf { it.sizeBytes }, trashed.size, trashed.sumOf { it.sizeBytes })
    }
    override suspend fun importFromUri(uri: Uri, folderId: FileFolderId?, displayName: String?, sourceApp: String?, onDuplicate: DuplicatePolicy, onProgress: (Long, Long?) -> Unit): ImportResult = error("unused")
    override suspend fun importStream(open: () -> java.io.InputStream, displayName: String, folderId: FileFolderId?, declaredMime: String?, declaredSize: Long?, sourceApp: String?, onDuplicate: DuplicatePolicy, onProgress: (Long, Long?) -> Unit): ImportResult = error("unused")
    override suspend fun resolveDuplicate(pending: PendingImport, keepBoth: Boolean): LibraryFile? = null
    override suspend fun rename(id: FileId, name: String): String {
        require(name.isNotBlank())
        renamed += id to name
        files.value = files.value.map { if (it.id == id) it.copy(displayName = name) else it }
        return name
    }
    override suspend fun moveFiles(ids: List<FileId>, folderId: FileFolderId?) = Unit
    override suspend fun setFavorite(ids: List<FileId>, favorite: Boolean) = Unit
    override suspend fun updateMetadata(id: FileId, width: Int?, height: Int?, durationMs: Long?, pageCount: Int?) { metadata += Triple(id, pageCount, durationMs) }
    override fun observeTrash(): Flow<FilesTrash> = files.map { l -> FilesTrash(emptyList(), l.filter { it.isInTrash }) }
    override suspend fun trash(fileIds: List<FileId>, folderIds: List<FileFolderId>) {
        files.value = files.value.map { if (it.id in fileIds) it.copy(trashedAt = 1L) else it }
    }
    override suspend fun restore(fileIds: List<FileId>, folderIds: List<FileFolderId>) {
        files.value = files.value.map { if (it.id in fileIds) it.copy(trashedAt = null) else it }
    }
    override suspend fun deletePermanently(fileIds: List<FileId>, folderIds: List<FileFolderId>) { files.value = files.value.filter { it.id !in fileIds } }
    override suspend fun emptyTrash() { files.value = live(files.value) }
    override suspend fun purgeExpiredTrash() = Unit
    override fun contentUri(file: LibraryFile): Uri = error("unused")
    override fun observeUsage(id: FileId): Flow<List<FileUsage>> = emptyFlow()
    override suspend fun usageCount(ids: List<FileId>) = 0
}

internal fun libraryFile(
    id: String, name: String = "$id.txt", mime: String = "text/plain", category: FileCategory = FileCategory.Text,
    folder: String? = null, size: Long = 10, trashedAt: Long? = null,
) = LibraryFile(
    FileId(id), folder?.let(::FileFolderId), name, name.substringAfterLast('.', ""), mime, category, size, id,
    createdAt = 0, modifiedAt = 0, trashedAt = trashedAt,
)

internal fun image(id: String, folder: String? = null) = libraryFile(id, "$id.png", "image/png", FileCategory.Image, folder)

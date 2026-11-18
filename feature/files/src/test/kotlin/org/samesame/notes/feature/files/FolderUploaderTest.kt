package org.samesame.notes.feature.files

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.samesame.notes.core.data.repository.FilesRepository
import org.samesame.notes.core.files.UploadChild
import org.samesame.notes.core.files.UploadTreeSource
import org.samesame.notes.core.model.files.*

private fun libraryFile(name: String) = LibraryFile(
    FileId("id-$name"), null, name, "txt", "text/plain", FileCategory.Text, 3, "sha-$name", createdAt = 0, modifiedAt = 0,
)

class FolderUploaderTest {
    private class Tree(private val byParent: Map<String?, List<UploadChild<String>>>) : UploadTreeSource<String> {
        override suspend fun children(parentId: String?) = byParent[parentId].orEmpty()
    }

    private class FakeRepo : FilesRepository {
        val folders = MutableStateFlow<List<FileFolder>>(emptyList())
        val deleted = ArrayList<Pair<List<FileId>, List<FileFolderId>>>()
        val importedNames = ArrayList<String>()
        /** Throw for a name to simulate a per-file import failure. */
        var failWith: (String) -> Exception? = { null }
        /** Signals the first import, then suspends until cancelled. */
        var onFirstImport: CompletableDeferred<Unit>? = null

        override fun observeFolders() = folders
        override fun observeTags(): kotlinx.coroutines.flow.Flow<List<String>> = kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun moveFileWithin(id: FileId, targetIndex: Int) = Unit
        override suspend fun moveFolderWithin(id: FileFolderId, targetIndex: Int) = Unit
        override suspend fun setDescription(id: FileId, description: String) = Unit
        override suspend fun setTags(id: FileId, tags: List<String>) = Unit
        override suspend fun createFolder(name: String, parentId: FileFolderId?, color: Long?): FileFolderId {
            val id = FileFolderId("f${folders.value.size + 1}")
            folders.value += FileFolder(id, parentId, name, null, 0, 0, 0, 0, 0)
            return id
        }
        override suspend fun importStream(
            open: () -> java.io.InputStream, displayName: String, folderId: FileFolderId?, declaredMime: String?,
            declaredSize: Long?, sourceApp: String?, onDuplicate: DuplicatePolicy, onProgress: (Long, Long?) -> Unit,
        ): ImportResult {
            importedNames += displayName
            onFirstImport?.let { started -> started.complete(Unit); kotlinx.coroutines.awaitCancellation() }
            failWith(displayName)?.let { throw it }
            return ImportResult.Imported(libraryFile(displayName))
        }
        override suspend fun deletePermanently(fileIds: List<FileId>, folderIds: List<FileFolderId>) {
            deleted += fileIds to folderIds
            folders.value = folders.value.filterNot { it.id in folderIds }
        }

        override suspend fun renameFolder(id: FileFolderId, name: String) = Unit
        override suspend fun setFolderColor(id: FileFolderId, color: Long?) = Unit
        override suspend fun moveFolder(id: FileFolderId, newParentId: FileFolderId?) = Unit
        override suspend fun folderPath(id: FileFolderId) = emptyList<FileFolder>()
        override fun observeFiles(folderId: FileFolderId?, sort: FileSort): Flow<List<LibraryFile>> = flowOf(emptyList())
        override fun observeAllFiles(sort: FileSort, categories: Set<FileCategory>?) = flowOf(emptyList<LibraryFile>())
        override fun observeFile(id: FileId) = flowOf<LibraryFile?>(null)
        override suspend fun getFile(id: FileId) = null
        override suspend fun getFiles(ids: List<FileId>) = emptyList<LibraryFile>()
        override fun search(query: String) = flowOf(emptyList<LibraryFile>())
        override fun observeSummary() = flowOf(FilesSummary())
        override suspend fun importFromUri(
            uri: Uri, folderId: FileFolderId?, displayName: String?, sourceApp: String?, onDuplicate: DuplicatePolicy,
            onProgress: (Long, Long?) -> Unit,
        ): ImportResult = error("unused")
        override suspend fun resolveDuplicate(pending: PendingImport, keepBoth: Boolean) = null
        override suspend fun rename(id: FileId, name: String) = name
        override suspend fun moveFiles(ids: List<FileId>, folderId: FileFolderId?) = Unit
        override suspend fun setFavorite(ids: List<FileId>, favorite: Boolean) = Unit
        override suspend fun updateMetadata(id: FileId, width: Int?, height: Int?, durationMs: Long?, pageCount: Int?) = Unit
        override suspend fun updateContent(id: FileId, open: () -> java.io.InputStream): LibraryFile = error("unused")
        override fun observeTrash() = flowOf(FilesTrash(emptyList(), emptyList()))
        override suspend fun trash(fileIds: List<FileId>, folderIds: List<FileFolderId>) = Unit
        override suspend fun restore(fileIds: List<FileId>, folderIds: List<FileFolderId>) = Unit
        override suspend fun emptyTrash() = Unit
        override suspend fun purgeExpiredTrash() = Unit
        override fun contentUri(file: LibraryFile): Uri = error("unused")
        override fun observeUsage(id: FileId): Flow<List<FileUsage>> = emptyFlow()
        override suspend fun usageCount(ids: List<FileId>) = 0
    }

    private fun tree() = Tree(
        mapOf(
            null to listOf(
                UploadChild("d1", "Pictures", true),
                UploadChild("f0", "root.txt", false),
            ),
            "d1" to listOf(UploadChild("f1", "inside.txt", false)),
        ),
    )

    @Test fun mapsTreeToFoldersAndReportsProgress() = runTest {
        val repo = FakeRepo()
        val progress = ArrayList<ImportProgress>()
        val summary = FolderUploader(repo).upload(
            source = tree(), openFile = { "x".byteInputStream() }, destination = null, onProgress = { progress += it },
        )
        assertThat(summary.imported).isEqualTo(2)
        assertThat(summary.skippedCount).isEqualTo(0)
        assertThat(repo.folders.value.map { it.name }).containsExactly("Pictures")
        assertThat(repo.importedNames).containsExactly("inside.txt", "root.txt").inOrder()
        assertThat(progress.map { it.name }.filter { it.isNotBlank() }).containsExactly("inside.txt", "root.txt").inOrder()
    }

    @Test fun classifiesPerFileFailuresAndKeepsGoing() = runTest {
        val repo = FakeRepo().apply {
            failWith = { name -> if (name == "inside.txt") LibraryFileTooLargeException("too big") else null }
        }
        val summary = FolderUploader(repo).upload(
            source = tree(), openFile = { "x".byteInputStream() }, destination = null, onProgress = {},
        )
        assertThat(summary.imported).isEqualTo(1)
        assertThat(summary.failures).containsExactly(ImportFailure("inside.txt", ImportFailureReason.TooLarge))
    }

    @Test fun cancelRollsBackFoldersAndImportedFiles() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo().apply { onFirstImport = gate }
        val job = launch {
            try {
                FolderUploader(repo).upload(tree(), { "x".byteInputStream() }, null, {})
                error("the upload should have been cancelled")
            } catch (c: CancellationException) {
                // expected
            }
        }
        // Wait until the import pipeline is holding the first file, then cancel mid-upload.
        gate.await()
        job.cancel()
        job.join()
        assertThat(repo.deleted).hasSize(1)
        assertThat(repo.deleted.single().second.map { it.value }).contains("f1")
        assertThat(repo.folders.value).isEmpty()
    }
}

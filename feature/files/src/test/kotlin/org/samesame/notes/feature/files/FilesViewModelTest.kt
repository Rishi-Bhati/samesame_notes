package org.samesame.notes.feature.files

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.samesame.notes.core.data.repository.FilesRepository
import org.samesame.notes.core.model.files.*

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakePrefs : FilesPreferences {
        override var sort = FileSort()
        override var viewMode = FilesViewMode.Grid
        override var lastFolderId: String? = null
    }

    private class FakeRepo : FilesRepository {
        val folders = MutableStateFlow<List<FileFolder>>(emptyList())
        val files = MutableStateFlow<List<LibraryFile>>(emptyList())
        val trashed = ArrayList<Pair<List<FileId>, List<FileFolderId>>>()
        val moved = ArrayList<Pair<List<FileId>, FileFolderId?>>()
        val movedFolders = ArrayList<Pair<FileFolderId, FileFolderId?>>()
        var lastSort: FileSort? = null

        override fun observeFolders() = folders
        override suspend fun createFolder(name: String, parentId: FileFolderId?, color: Long?): FileFolderId {
            require(name.isNotBlank())
            val id = FileFolderId("f${folders.value.size + 1}")
            folders.value += FileFolder(id, parentId, name, color, 0, 0, 0, 0, 0)
            return id
        }
        override suspend fun renameFolder(id: FileFolderId, name: String) = Unit
        override suspend fun setFolderColor(id: FileFolderId, color: Long?) = Unit
        override suspend fun moveFolder(id: FileFolderId, newParentId: FileFolderId?) { movedFolders += id to newParentId }
        override suspend fun folderPath(id: FileFolderId) = emptyList<FileFolder>()
        override fun observeFiles(folderId: FileFolderId?, sort: FileSort): Flow<List<LibraryFile>> {
            lastSort = sort
            return files.map { list -> list.filter { it.folderId == folderId } }
        }
        override fun observeAllFiles(sort: FileSort, categories: Set<FileCategory>?) = files
        override fun observeFile(id: FileId) = files.map { l -> l.firstOrNull { it.id == id } }
        override suspend fun getFile(id: FileId) = files.value.firstOrNull { it.id == id }
        override suspend fun getFiles(ids: List<FileId>) = files.value.filter { it.id in ids }
        override fun search(query: String) = flowOf(files.value.filter { it.displayName.contains(query) })
        override fun observeSummary() = files.map { FilesSummary(it.size, it.sumOf { f -> f.sizeBytes }) }
        override suspend fun importFromUri(uri: Uri, folderId: FileFolderId?, displayName: String?, sourceApp: String?, onDuplicate: DuplicatePolicy, onProgress: (Long, Long?) -> Unit): ImportResult = error("unused")
        override suspend fun importStream(open: () -> java.io.InputStream, displayName: String, folderId: FileFolderId?, declaredMime: String?, declaredSize: Long?, sourceApp: String?, onDuplicate: DuplicatePolicy, onProgress: (Long, Long?) -> Unit): ImportResult = error("unused")
        override suspend fun resolveDuplicate(pending: PendingImport, keepBoth: Boolean): LibraryFile? = null
        override suspend fun rename(id: FileId, name: String): String { require(name.isNotBlank()); return name }
        override suspend fun moveFiles(ids: List<FileId>, folderId: FileFolderId?) { moved += ids to folderId }
        override suspend fun setFavorite(ids: List<FileId>, favorite: Boolean) = Unit
        override suspend fun updateMetadata(id: FileId, width: Int?, height: Int?, durationMs: Long?, pageCount: Int?) = Unit
        override fun observeTrash(): Flow<FilesTrash> = flowOf(FilesTrash(emptyList(), emptyList()))
        override suspend fun trash(fileIds: List<FileId>, folderIds: List<FileFolderId>) { trashed += fileIds to folderIds }
        override suspend fun restore(fileIds: List<FileId>, folderIds: List<FileFolderId>) = Unit
        override suspend fun deletePermanently(fileIds: List<FileId>, folderIds: List<FileFolderId>) = Unit
        override suspend fun emptyTrash() = Unit
        override suspend fun purgeExpiredTrash() = Unit
        override fun contentUri(file: LibraryFile): Uri = error("unused")
        override fun observeUsage(id: FileId): Flow<List<FileUsage>> = emptyFlow()
        override suspend fun usageCount(ids: List<FileId>) = 0
    }

    private fun file(id: String, folder: String? = null) =
        LibraryFile(FileId(id), folder?.let(::FileFolderId), "$id.txt", "txt", "text/plain", FileCategory.Text, 10, id, createdAt = 0, modifiedAt = 0)

    private fun folder(id: String, parent: String? = null) =
        FileFolder(FileFolderId(id), parent?.let(::FileFolderId), id, null, 0, if (parent == null) 0 else 1, 0, 0, 0)

    private fun TestScope2.vm() = FilesViewModel(repo, prefs)
    private class TestScope2(val repo: FakeRepo = FakeRepo(), val prefs: FakePrefs = FakePrefs())

    @Test fun showsRootFilesAndFoldersThenNavigates() = runTest {
        val s = TestScope2()
        s.repo.folders.value = listOf(folder("a"), folder("b", "a"))
        s.repo.files.value = listOf(file("x"), file("y", "a"))
        val vm = s.vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertThat(vm.uiState.value.subFolders.map { it.id.value }).containsExactly("a")
        assertThat(vm.uiState.value.files.map { it.id.value }).containsExactly("x")
        vm.openFolder(FileFolderId("a"))
        assertThat(vm.uiState.value.files.map { it.id.value }).containsExactly("y")
        assertThat(vm.uiState.value.subFolders.map { it.id.value }).containsExactly("b")
        assertThat(vm.uiState.value.title).isEqualTo("a")
        assertThat(vm.navigateUp()).isTrue()
        assertThat(vm.uiState.value.folderId).isNull()
        assertThat(vm.navigateUp()).isFalse()
    }

    @Test fun selectionToggleSelectAllAndClear() = runTest {
        val s = TestScope2()
        s.repo.folders.value = listOf(folder("a"))
        s.repo.files.value = listOf(file("x"), file("y"))
        val vm = s.vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.startSelecting(file = file("x"))
        assertThat(vm.uiState.value.selecting).isTrue()
        vm.toggle(file("y"))
        assertThat(vm.uiState.value.selection.count).isEqualTo(2)
        vm.toggleSelectAll()
        assertThat(vm.uiState.value.allSelected).isTrue()
        vm.toggleSelectAll()
        assertThat(vm.uiState.value.selection.count).isEqualTo(0)
        assertThat(vm.uiState.value.selecting).isTrue()
        vm.clearSelection()
        assertThat(vm.uiState.value.selecting).isFalse()
    }

    @Test fun trashAndMoveUseTheSelection() = runTest {
        val s = TestScope2()
        s.repo.folders.value = listOf(folder("a"), folder("t"))
        s.repo.files.value = listOf(file("x"))
        val vm = s.vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.startSelecting(file = file("x"))
        vm.toggle(folder("a"))
        vm.moveSelection(FileFolderId("t"))
        assertThat(s.repo.moved).containsExactly(listOf(FileId("x")) to FileFolderId("t"))
        assertThat(s.repo.movedFolders).containsExactly(FileFolderId("a") to FileFolderId("t"))
        vm.startSelecting(file = file("x"))
        vm.trashSelection()
        assertThat(s.repo.trashed.single().first).containsExactly(FileId("x"))
    }

    @Test fun folderCannotMoveIntoItsOwnSubtree() = runTest {
        val s = TestScope2()
        s.repo.folders.value = listOf(folder("a"), folder("b", "a"))
        val vm = s.vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.startSelecting(folder = folder("a"))
        vm.moveSelection(FileFolderId("b"))
        assertThat(s.repo.movedFolders).isEmpty()
        assertThat(vm.events.first()).isEqualTo(FilesEvent.Failure(FailureKind.TooDeep))
    }

    @Test fun sortAndViewModeArePersisted() = runTest {
        val s = TestScope2()
        val vm = s.vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.setSortField(FileSortField.Name)
        assertThat(s.prefs.sort).isEqualTo(FileSort(FileSortField.Name, ascending = true))
        vm.toggleSortDirection()
        assertThat(s.prefs.sort.ascending).isFalse()
        vm.setViewMode(FilesViewMode.List)
        assertThat(s.prefs.viewMode).isEqualTo(FilesViewMode.List)
        assertThat(vm.uiState.value.viewMode).isEqualTo(FilesViewMode.List)
    }

    @Test fun openFolderThatDisappearsFallsBackToRoot() = runTest {
        val s = TestScope2()
        s.repo.folders.value = listOf(folder("a"))
        val vm = s.vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.openFolder(FileFolderId("a"))
        s.repo.folders.value = emptyList()
        assertThat(vm.uiState.value.folderId).isNull()
    }

    @Test fun linkTextHasOneLinkPerLine() = runTest {
        val vm = TestScope2().vm()
        val text = vm.linkText(listOf(file("00000000-0000-4000-8000-000000000001"), file("00000000-0000-4000-8000-000000000002")))
        assertThat(text.lines()).hasSize(2)
        assertThat(text.lines().all { it.startsWith("samesame://file/") }).isTrue()
    }
}

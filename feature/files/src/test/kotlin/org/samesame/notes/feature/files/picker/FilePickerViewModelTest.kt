package org.samesame.notes.feature.files.picker

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.samesame.notes.core.model.files.FileFolder
import org.samesame.notes.core.model.files.FileFolderId
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.feature.files.viewer.FakeFilesRepo
import org.samesame.notes.feature.files.viewer.FakePrefs
import org.samesame.notes.feature.files.viewer.libraryFile

@OptIn(ExperimentalCoroutinesApi::class)
class FilePickerViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun folder(id: String, parent: String? = null) =
        FileFolder(FileFolderId(id), parent?.let(::FileFolderId), id, null, 0, if (parent == null) 0 else 1, 0, 0, 0)

    private val repo = FakeFilesRepo().apply {
        folders.value = listOf(folder("a"), folder("b", "a"))
        files.value = listOf(
            libraryFile("root1", "report.txt"), libraryFile("root2", "photo.txt"),
            libraryFile("inA", "Report final.txt", folder = "a"), libraryFile("inB", "deep.txt", folder = "b"),
            libraryFile("binned", "report old.txt", trashedAt = 1L),
        )
    }

    private fun TestScope.vm(): FilePickerViewModel {
        val vm = FilePickerViewModel(repo, FakePrefs())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private fun FilePickerViewModel.ids() = uiState.value.files.map { it.id.value }

    @Test fun browsesFoldersAndGoesBackUp() = runTest {
        val vm = vm()
        assertThat(vm.ids()).containsExactly("root1", "root2")
        assertThat(vm.uiState.value.subFolders.map { it.id.value }).containsExactly("a")
        vm.openFolder(FileFolderId("a"))
        assertThat(vm.ids()).containsExactly("inA")
        assertThat(vm.uiState.value.title).isEqualTo("a")
        vm.openFolder(FileFolderId("b"))
        assertThat(vm.uiState.value.path.map { it.id.value }).containsExactly("a", "b").inOrder()
        assertThat(vm.navigateUp()).isTrue()
        assertThat(vm.uiState.value.folderId).isEqualTo(FileFolderId("a"))
        assertThat(vm.navigateUp()).isTrue()
        assertThat(vm.navigateUp()).isFalse()
    }

    @Test fun neverOffersRecycleBinFiles() = runTest {
        val vm = vm()
        assertThat(vm.ids()).doesNotContain("binned")
        vm.startSearch()
        vm.setQuery("report")
        assertThat(vm.ids()).containsExactly("root1", "inA")
    }

    @Test fun searchCoversEveryFolderAndBackLeavesIt() = runTest {
        val vm = vm()
        vm.openFolder(FileFolderId("a"))
        vm.startSearch()
        assertThat(vm.uiState.value.searching).isTrue()
        assertThat(vm.ids()).isEmpty() // nothing typed yet
        assertThat(vm.uiState.value.subFolders).isEmpty()
        vm.setQuery("deep")
        assertThat(vm.ids()).containsExactly("inB")
        assertThat(vm.navigateUp()).isTrue()
        assertThat(vm.uiState.value.searching).isFalse()
        assertThat(vm.uiState.value.folderId).isEqualTo(FileFolderId("a"))
    }

    @Test fun selectionSpansFoldersAndKeepsPickOrder() = runTest {
        val vm = vm()
        vm.toggle(repo.files.value.first { it.id.value == "root2" })
        vm.openFolder(FileFolderId("a"))
        vm.toggle(repo.files.value.first { it.id.value == "inA" })
        vm.startSearch(); vm.setQuery("report")
        vm.toggle(repo.files.value.first { it.id.value == "root1" })
        assertThat(vm.uiState.value.selected.map { it.value }).containsExactly("root2", "inA", "root1").inOrder()
        vm.toggle(repo.files.value.first { it.id.value == "inA" })
        assertThat(vm.confirmed().map { it.value }).containsExactly("root2", "root1").inOrder()
    }

    @Test fun confirmDropsFilesDeletedMeanwhileAndBinnedFilesCannotBePicked() = runTest {
        val vm = vm()
        vm.toggle(repo.files.value.first { it.id.value == "binned" })
        assertThat(vm.uiState.value.selected).isEmpty()
        vm.toggle(repo.files.value.first { it.id.value == "root1" })
        vm.toggle(repo.files.value.first { it.id.value == "root2" })
        repo.trash(listOf(FileId("root1")), emptyList())
        assertThat(vm.confirmed().map { it.value }).containsExactly("root2")
    }

    @Test fun resetStartsClean() = runTest {
        val vm = vm()
        vm.openFolder(FileFolderId("a"))
        vm.toggle(repo.files.value.first { it.id.value == "inA" })
        vm.reset()
        assertThat(vm.uiState.value.folderId).isNull()
        assertThat(vm.uiState.value.selected).isEmpty()
        assertThat(vm.uiState.value.searching).isFalse()
    }

    @Test fun aFolderThatDisappearsFallsBackToTheRoot() = runTest {
        val vm = vm()
        vm.openFolder(FileFolderId("a"))
        repo.folders.value = emptyList()
        assertThat(vm.uiState.value.folderId).isNull()
        assertThat(vm.ids()).containsExactly("root1", "root2")
    }
}

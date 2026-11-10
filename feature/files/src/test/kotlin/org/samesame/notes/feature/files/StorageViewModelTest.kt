package org.samesame.notes.feature.files

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.FilesSummary
import org.samesame.notes.feature.files.viewer.FakeFilesRepo
import org.samesame.notes.feature.files.viewer.libraryFile

@OptIn(ExperimentalCoroutinesApi::class)
class StorageViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun files() = listOf(
        libraryFile("a", "a.png", "image/png", FileCategory.Image, size = 300),
        libraryFile("b", "b.png", "image/png", FileCategory.Image, size = 200),
        libraryFile("c", "c.pdf", "application/pdf", FileCategory.Pdf, size = 1000),
        libraryFile("d", "d.txt", size = 5),
        libraryFile("gone", "gone.mp4", "video/mp4", FileCategory.Video, size = 9000, trashedAt = 1L),
    )

    @Test fun totalsCategoriesAndLargest() {
        val s = StorageUiState.from(files(), FilesSummary(4, 1505, 1, 9000), freeBytes = 5_000_000_000)
        assertThat(s.fileCount).isEqualTo(4)
        assertThat(s.totalBytes).isEqualTo(1505)
        assertThat(s.categories).containsExactly(
            CategoryUsage(FileCategory.Pdf, 1000, 1), CategoryUsage(FileCategory.Image, 500, 2), CategoryUsage(FileCategory.Text, 5, 1),
        ).inOrder()
        assertThat(s.largest.map { it.id.value }).containsExactly("c", "a", "b", "d").inOrder()
        assertThat(s.trashedCount).isEqualTo(1)
        assertThat(s.trashedBytes).isEqualTo(9000)
        assertThat(s.lowSpace).isFalse()
    }

    @Test fun largestIsCappedAndLowSpaceWarns() {
        val many = (1..25).map { libraryFile("f$it", size = it.toLong()) }
        val s = StorageUiState.from(many, FilesSummary(), freeBytes = Limits.LIBRARY_FREE_SPACE_RESERVE_BYTES - 1)
        assertThat(s.largest).hasSize(StorageUiState.LARGEST_COUNT)
        assertThat(s.largest.first().sizeBytes).isEqualTo(25)
        assertThat(s.lowSpace).isTrue()
        assertThat(StorageUiState.from(many, FilesSummary(), freeBytes = null).lowSpace).isFalse()
    }

    @Test fun emptyingTheBinUpdatesTheStateAndFreeSpace() = runTest {
        val repo = FakeFilesRepo().apply { files.value = files() }
        var free = 100L
        val vm = StorageViewModel(repo) { free }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertThat(vm.uiState.value.trashedBytes).isEqualTo(9000)
        assertThat(vm.uiState.value.freeBytes).isEqualTo(100)
        free = 9100
        vm.emptyTrash()
        assertThat(vm.uiState.value.trashedCount).isEqualTo(0)
        assertThat(vm.uiState.value.freeBytes).isEqualTo(9100)
        assertThat(vm.uiState.value.fileCount).isEqualTo(4)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FilesRevealTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun revealOpensTheFolderAndSelectsTheFile() = runTest {
        val repo = FakeFilesRepo().apply {
            folders.value = listOf(org.samesame.notes.core.model.files.FileFolder(org.samesame.notes.core.model.files.FileFolderId("a"), null, "a", null, 0, 0, 0, 0, 0))
            files.value = listOf(libraryFile("x"), libraryFile("y", folder = "a"), libraryFile("gone", trashedAt = 1L))
        }
        val vm = FilesViewModel(repo, org.samesame.notes.feature.files.viewer.FakePrefs())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val id = org.samesame.notes.core.model.files.FileId("y")
        vm.reveal(id)
        val s = vm.uiState.value
        assertThat(s.folderId?.value).isEqualTo("a")
        assertThat(s.selection.files).containsExactly(id)
        assertThat(s.reveal).isEqualTo(id)
        vm.revealShown()
        assertThat(vm.uiState.value.reveal).isNull()
        assertThat(vm.uiState.value.selecting).isTrue()
    }

    @Test fun aMissingOrBinnedFileIsReported() = runTest {
        val repo = FakeFilesRepo().apply { files.value = listOf(libraryFile("gone", trashedAt = 1L)) }
        val vm = FilesViewModel(repo, org.samesame.notes.feature.files.viewer.FakePrefs())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.reveal(org.samesame.notes.core.model.files.FileId("gone"))
        vm.reveal(org.samesame.notes.core.model.files.FileId("nope"))
        val events = ArrayList<FilesEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        assertThat(events).containsExactly(FilesEvent.Failure(FailureKind.NotFound), FilesEvent.Failure(FailureKind.NotFound))
        assertThat(vm.uiState.value.selecting).isFalse()
    }
}

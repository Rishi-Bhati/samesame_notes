package org.samesame.notes.feature.files.viewer

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.FileId

@OptIn(ExperimentalCoroutinesApi::class)
class FileViewerViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val repo = FakeFilesRepo()

    private fun TestScope.vm(): FileViewerViewModel {
        val vm = FileViewerViewModel(repo, FakePrefs()) { error("unused") }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    @Test fun loadingUntilStartedThenReady() = runTest {
        repo.files.value = listOf(libraryFile("t"))
        val vm = vm()
        assertThat(vm.uiState.value).isEqualTo(ViewerState.Loading)
        vm.start(FileId("t"))
        val s = vm.uiState.value as ViewerState.Ready
        assertThat(s.kind).isEqualTo(ViewerKind.Text)
        assertThat(s.images).isEmpty()
    }

    @Test fun unknownIdIsMissingAndTrashedIsTrashed() = runTest {
        repo.files.value = listOf(libraryFile("t", trashedAt = 5L))
        val vm = vm()
        vm.start(FileId("nope"))
        assertThat(vm.uiState.value).isEqualTo(ViewerState.Missing)

        val vm2 = vm()
        vm2.start(FileId("t"))
        assertThat(vm2.uiState.value).isInstanceOf(ViewerState.Trashed::class.java)
        vm2.restore(FileId("t"))
        assertThat(vm2.uiState.value).isInstanceOf(ViewerState.Ready::class.java)
    }

    @Test fun imagesOfTheSameFolderAreTheSwipeSet() = runTest {
        repo.files.value = listOf(
            image("a", "f"), libraryFile("doc", folder = "f"), image("b", "f"), image("other"),
            libraryFile("svg", "x.svg", "image/svg+xml", FileCategory.Image, "f"), image("gone", "f").copy(trashedAt = 1L),
        )
        val vm = vm()
        vm.start(FileId("b"))
        val s = vm.uiState.value as ViewerState.Ready
        assertThat(s.kind).isEqualTo(ViewerKind.Image)
        assertThat(s.images.map { it.id.value }).containsExactly("a", "b").inOrder()
        assertThat(s.imageIndex).isEqualTo(1)
    }

    @Test fun swipingChangesTheFileOnScreenButNotTheSet() = runTest {
        repo.files.value = listOf(image("a", "f"), image("b", "f"), image("other"))
        val vm = vm()
        vm.start(FileId("a"))
        vm.showImage(FileId("b"))
        val s = vm.uiState.value as ViewerState.Ready
        assertThat(s.file.id.value).isEqualTo("b")
        assertThat(s.images).hasSize(2)
        // Not part of the set: ignored.
        vm.showImage(FileId("other"))
        assertThat((vm.uiState.value as ViewerState.Ready).file.id.value).isEqualTo("b")
        // A second start (recreated host) keeps the page.
        vm.start(FileId("a"))
        assertThat((vm.uiState.value as ViewerState.Ready).file.id.value).isEqualTo("b")
    }

    @Test fun deleteTrashesAndTellsTheScreenToClose() = runTest {
        repo.files.value = listOf(libraryFile("t"))
        val vm = vm()
        vm.start(FileId("t"))
        vm.delete(FileId("t"))
        assertThat(vm.events.first()).isEqualTo(ViewerEvent.Deleted)
        assertThat(repo.files.value.single().isInTrash).isTrue()
    }

    @Test fun renameUpdatesTheTitleAndBadNamesAreReported() = runTest {
        repo.files.value = listOf(libraryFile("t"))
        val vm = vm()
        vm.start(FileId("t"))
        vm.rename(FileId("t"), "new.txt")
        assertThat((vm.uiState.value as ViewerState.Ready).file.displayName).isEqualTo("new.txt")
        vm.rename(FileId("t"), " ")
        assertThat(vm.events.first()).isEqualTo(ViewerEvent.RenameFailed)
    }

    @Test fun learnedMetadataIsStoredOnce() = runTest {
        val f = libraryFile("p", "p.pdf", "application/pdf", FileCategory.Pdf)
        repo.files.value = listOf(f)
        val vm = vm()
        vm.learned(f, pageCount = 3)
        vm.learned(f.copy(pageCount = 3), pageCount = 3)
        assertThat(repo.metadata).containsExactly(Triple(FileId("p"), 3, null))
    }
}

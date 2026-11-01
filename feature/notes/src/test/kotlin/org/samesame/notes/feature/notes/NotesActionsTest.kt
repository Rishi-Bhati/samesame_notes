package org.samesame.notes.feature.notes

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.samesame.notes.core.data.pdf.PdfImportError
import org.samesame.notes.core.data.pdf.PdfImportException
import org.samesame.notes.core.data.repository.FolderRepository
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.data.repository.UserPreferencesRepository
import org.samesame.notes.core.model.Folder
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.UserPreferences
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class NotesActionsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun note(id: String) = Note(NoteId(id), id, "", null, false, false, PageMode.Pages, 0, 0, 0, 0, null)

    private class Calls {
        val moves = ArrayList<Pair<List<NoteId>, FolderId?>>()
        val pdfImports = ArrayList<Triple<List<File>, String, FolderId?>>()
        val noteTrash = ArrayList<List<NoteId>>()
        val noteRestore = ArrayList<List<NoteId>>()
        val noteDelete = ArrayList<List<NoteId>>()
        val folderTrash = ArrayList<List<FolderId>>()
        val folderRestore = ArrayList<List<FolderId>>()
        val folderDelete = ArrayList<List<FolderId>>()
        var failPdf: Throwable? = null
    }

    private fun fakeNotes(all: List<Note>, calls: Calls): NoteRepository =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(NoteRepository::class.java)) { _, method, args ->
            try {
                when (method.name.substringBefore('-')) { // inline-class parameters mangle the name
                    "observeNotes" -> flowOf(all)
                    "observeNoteCounts" -> flowOf(all.size to all.count { it.folderId == null })
                    "purgeExpiredTrash" -> Unit
                    "moveToFolder" -> { calls.moves += (args[0] as List<NoteId>) to (args[1]?.let { FolderId(it.toString()) }); Unit }
                    "moveToTrash" -> { calls.noteTrash += args[0] as List<NoteId>; Unit }
                    "restoreFromTrash" -> { calls.noteRestore += args[0] as List<NoteId>; Unit }
                    "deleteForever" -> { calls.noteDelete += args[0] as List<NoteId>; Unit }
                    "importPdf" -> {
                        calls.failPdf?.let { throw it }
                        calls.pdfImports += Triple(args[0] as List<File>, args[1] as String, args[2]?.let { FolderId(it.toString()) })
                        "pdf-${calls.pdfImports.size}" // an inline-class result comes back unboxed
                    }
                    else -> throw UnsupportedOperationException(method.name)
                }
            } catch (e: InvocationTargetException) { throw e.targetException }
        } as NoteRepository

    private fun fakeFolders(calls: Calls) = object : FolderRepository {
        override fun observeFolders(): Flow<List<Folder>> = flowOf(emptyList())
        override fun observeTrashedFolders(): Flow<List<Folder>> = flowOf(emptyList())
        override suspend fun createFolder(name: String, parentId: FolderId?, color: Long?) = FolderId("new")
        override suspend fun rename(id: FolderId, name: String) = Unit
        override suspend fun setColor(id: FolderId, color: Long?) = Unit
        override suspend fun trash(ids: List<FolderId>) { calls.folderTrash += ids }
        override suspend fun restore(ids: List<FolderId>) { calls.folderRestore += ids }
        override suspend fun deletePermanently(ids: List<FolderId>) { calls.folderDelete += ids }
        override suspend fun purgeExpiredTrash() = Unit
    }

    private val fakePrefs = object : UserPreferencesRepository {
        override val preferences = MutableStateFlow(UserPreferences())
        override suspend fun setViewMode(mode: org.samesame.notes.core.model.ViewMode) = Unit
        override suspend fun setSortField(field: org.samesame.notes.core.model.SortField) = Unit
        override suspend fun setSortDirection(direction: org.samesame.notes.core.model.SortDirection) = Unit
        override suspend fun setPinFavoritesToTop(pin: Boolean) = Unit
        override suspend fun setShowFolderCards(show: Boolean) = Unit
    }

    private fun viewModel(calls: Calls, notes: List<Note>) = NotesViewModel(fakeNotes(notes, calls), fakeFolders(calls), fakePrefs)

    @Test
    fun moveSendsTheSelectionToTheChosenFolderAndLeavesSelectionMode() = runTest(dispatcher) {
        val calls = Calls()
        val vm = viewModel(calls, listOf(note("a"), note("b"), note("c")))
        val job = backgroundScope.launch(dispatcher) { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.startSelecting(note("a"))
        vm.toggleSelected(note("c"))
        advanceUntilIdle()

        vm.moveSelectionTo(FolderId("work"))
        advanceUntilIdle()

        assertThat(calls.moves).hasSize(1)
        assertThat(calls.moves[0].first.map { it.value }).containsExactly("a", "c")
        assertThat(calls.moves[0].second).isEqualTo(FolderId("work"))
        assertThat(vm.uiState.value.selecting).isFalse()
        job.cancel()
    }

    @Test
    fun moveToTheTopLevelUsesANullFolder() = runTest(dispatcher) {
        val calls = Calls()
        val vm = viewModel(calls, listOf(note("a")))
        val job = backgroundScope.launch(dispatcher) { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.startSelecting(note("a"))
        advanceUntilIdle()
        vm.moveSelectionTo(null)
        advanceUntilIdle()
        assertThat(calls.moves.single().second).isNull()
        job.cancel()
    }

    @Test
    fun pdfPlansFollowTheMultiPdfChoice() {
        val a = PdfSource(File("a.pdf"), "A")
        val b = PdfSource(File("b.pdf"), "B")
        assertThat(planPdfImports(listOf(a, b), oneNote = true)).containsExactly(PdfImportJob(listOf(a.file, b.file), "A"))
        assertThat(planPdfImports(listOf(a, b), oneNote = false))
            .containsExactly(PdfImportJob(listOf(a.file), "A"), PdfImportJob(listOf(b.file), "B")).inOrder()
        assertThat(planPdfImports(emptyList(), oneNote = false)).isEmpty()
    }

    @Test
    fun pdfTitleDropsTheExtension() {
        assertThat(pdfTitle("Lecture 3.pdf")).isEqualTo("Lecture 3")
        assertThat(pdfTitle("SCAN.PDF")).isEqualTo("SCAN")
        assertThat(pdfTitle("notes.pdf.pdf")).isEqualTo("notes.pdf")
        assertThat(pdfTitle(null)).isEmpty()
    }

    @Test
    fun importCreatesOneNotePerJobAndKeepsGoingAfterAFailure() = runTest(dispatcher) {
        val calls = Calls()
        val vm = viewModel(calls, emptyList())
        val ok = vm.importPdfs(listOf(PdfImportJob(listOf(File("a.pdf")), "A"), PdfImportJob(listOf(File("b.pdf")), "B")))
        assertThat(ok.created.map { it.value }).containsExactly("pdf-1", "pdf-2").inOrder()
        assertThat(ok.error).isNull()
        assertThat(calls.pdfImports.map { it.second }).containsExactly("A", "B").inOrder()

        calls.failPdf = PdfImportException(PdfImportError.PasswordProtected)
        val failed = vm.importPdfs(listOf(PdfImportJob(listOf(File("c.pdf")), "C")))
        assertThat(failed.created).isEmpty()
        assertThat(failed.error).isEqualTo(PdfImportError.PasswordProtected)
    }

    private fun folder(id: String) = Folder(FolderId(id), null, id, null, 0, 0, 0, null)

    @Test
    fun trashSelectionMovesSelectedFoldersAndNotesTogether() = runTest(dispatcher) {
        val calls = Calls()
        val vm = viewModel(calls, listOf(note("a"), note("b")))
        val job = backgroundScope.launch(dispatcher) { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.startSelecting(note("a"))
        vm.toggleSelected(folder("f"))
        advanceUntilIdle()

        vm.moveSelectionToTrash()
        advanceUntilIdle()

        assertThat(calls.folderTrash.single().map { it.value }).containsExactly("f")
        assertThat(calls.noteTrash.single().map { it.value }).containsExactly("a")
        assertThat(vm.uiState.value.selecting).isFalse()
        assertThat(vm.uiState.value.folderSelection).isEmpty()
        job.cancel()
    }

    @Test
    fun restoreSelectionRestoresFoldersAndNotes() = runTest(dispatcher) {
        val calls = Calls()
        val vm = viewModel(calls, emptyList())
        val job = backgroundScope.launch(dispatcher) { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.startSelecting(firstFolder = folder("f"))
        advanceUntilIdle()

        vm.restoreSelection()
        advanceUntilIdle()

        assertThat(calls.folderRestore.single().map { it.value }).containsExactly("f")
        assertThat(calls.noteRestore).isEmpty()
        job.cancel()
    }

    @Test
    fun permanentDeleteRemovesFoldersAndNotes() = runTest(dispatcher) {
        val calls = Calls()
        val vm = viewModel(calls, emptyList())
        val job = backgroundScope.launch(dispatcher) { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.startSelecting(firstFolder = folder("f"))
        advanceUntilIdle()

        vm.deleteSelectionForever()
        advanceUntilIdle()

        assertThat(calls.folderDelete.single().map { it.value }).containsExactly("f")
        job.cancel()
    }

    @Test
    fun manageFoldersTrashLeavesSelectionMode() = runTest(dispatcher) {
        val calls = Calls()
        val vm = viewModel(calls, emptyList())

        vm.trashFolders(listOf(FolderId("f")))
        advanceUntilIdle()

        assertThat(calls.folderTrash.single().map { it.value }).containsExactly("f")
        assertThat(vm.uiState.value.folderSelection).isEmpty()
    }

    @Test
    fun restoreToastFollowsTheSelectionMix() {
        assertThat(restoreToast(1, 0)).isEqualTo(RestoreToast.Folder)
        assertThat(restoreToast(2, 0)).isEqualTo(RestoreToast.Folders)
        assertThat(restoreToast(1, 1)).isEqualTo(RestoreToast.Items)
        assertThat(restoreToast(0, 1)).isEqualTo(RestoreToast.Note)
        assertThat(restoreToast(0, 2)).isEqualTo(RestoreToast.Notes)
    }

    @Test
    fun trashDialogWordingCoversFoldersNotesAndContents() {
        assertThat(trashDialogText(1, 1, false).message).isEqualTo(R.string.feature_notes_trash_msg_one_folder_one_note)
        assertThat(trashDialogText(2, 3, false).message).isEqualTo(R.string.feature_notes_trash_msg_n_folder_n_note)
        assertThat(trashDialogText(1, 0, true).message).isEqualTo(R.string.feature_notes_trash_msg_one_folder_has_child)
        assertThat(trashDialogText(2, 0, true).message).isEqualTo(R.string.feature_notes_trash_msg_n_folder_has_child)
        assertThat(trashDialogText(1, 2, true).message).isEqualTo(R.string.feature_notes_trash_msg_one_folder_has_child_n_note)
        assertThat(trashDialogText(0, 2, false).message).isEqualTo(R.string.feature_notes_trash_msg_n_note)
    }

    @Test
    fun permanentDeleteDialogUsesTheSameShapes() {
        assertThat(permanentDeleteDialogText(1, 1, true).message)
            .isEqualTo(R.string.feature_notes_delete_msg_one_folder_has_child_one_note)
        assertThat(permanentDeleteDialogText(2, 3, false).message)
            .isEqualTo(R.string.feature_notes_delete_msg_n_folder_n_note)
        assertThat(permanentDeleteDialogText(0, 1, false).message)
            .isEqualTo(R.string.feature_notes_delete_msg_one_note)
    }
}

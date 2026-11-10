package org.samesame.notes.feature.notes

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.samesame.notes.core.data.repository.FolderRepository
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.data.repository.UserPreferencesRepository
import org.samesame.notes.core.model.Folder
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteCounts
import org.samesame.notes.core.model.UserPreferences
import java.lang.reflect.Proxy

/** Subfolder creation (rel012-subfolder): parent forwarding, depth guard and tree display order. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubfolderTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun folder(id: String, parentId: String? = null, depth: Int = 0) = Folder(
        id = FolderId(id),
        parentId = parentId?.let(::FolderId),
        name = id,
        color = null,
        sortOrder = 0,
        depth = depth,
        noteCount = 0,
        trashedAt = null,
    )

    // ---- canCreateSubfolder: mirrors FolderRepository.createFolder (parentDepth + 1 < MAX) ----

    @Test
    fun subfolderIsAllowedWellWithinTheLimit() {
        assertThat(canCreateSubfolder(0)).isTrue()
        assertThat(canCreateSubfolder(1)).isTrue()
    }

    @Test
    fun subfolderIsAllowedUpToTheLastPermittedDepth() {
        // Child gets depth parentDepth + 1; the repository permits child depth MAX - 1.
        assertThat(canCreateSubfolder(Limits.MAX_FOLDER_DEPTH - 2)).isTrue()
    }

    @Test
    fun subfolderIsRefusedPastTheLimit() {
        assertThat(canCreateSubfolder(Limits.MAX_FOLDER_DEPTH - 1)).isFalse()
        assertThat(canCreateSubfolder(Limits.MAX_FOLDER_DEPTH)).isFalse()
        assertThat(canCreateSubfolder(Limits.MAX_FOLDER_DEPTH + 10)).isFalse()
    }

    // ---- orderedFolders: child directly after its parent, siblings keep repository order ----

    @Test
    fun orderedFoldersPutsChildrenDirectlyUnderTheirParent() {
        // Repository lists alphabetically: a, b(child of a?), ... — flat order must not matter.
        val folders = listOf(
            folder("b", parentId = "a", depth = 1),
            folder("a", depth = 0),
            folder("c", depth = 0),
            folder("d", parentId = "b", depth = 2),
            folder("e", parentId = "a", depth = 1),
        )

        assertThat(orderedFolders(folders).map { it.id })
            .containsExactly(FolderId("a"), FolderId("b"), FolderId("d"), FolderId("e"), FolderId("c"))
            .inOrder()
    }

    @Test
    fun orderedFoldersKeepsFlatFoldersUnchanged() {
        val folders = listOf(folder("x"), folder("y"), folder("z"))

        assertThat(orderedFolders(folders).map { it.id })
            .containsExactly(FolderId("x"), FolderId("y"), FolderId("z")).inOrder()
    }

    @Test
    fun orderedFoldersTreatsAMissingParentAsTopLevel() {
        val folders = listOf(folder("orphan", parentId = "gone", depth = 1), folder("top"))

        assertThat(orderedFolders(folders).map { it.id })
            .containsExactly(FolderId("orphan"), FolderId("top")).inOrder()
    }

    @Test
    fun orderedFoldersKeepsEveryFolderOnce() {
        val folders = listOf(
            folder("child", parentId = "parent", depth = 1),
            folder("parent", depth = 0),
            folder("other", depth = 0),
        )

        assertThat(orderedFolders(folders)).hasSize(3)
        assertThat(orderedFolders(folders).map { it.id }.toSet())
            .containsExactly(FolderId("parent"), FolderId("child"), FolderId("other"))
    }

    // ---- ViewModel forwarding: createFolderUnder passes the explicit parent through ----

    private class CreateCalls {
        val created = ArrayList<Triple<String, Long?, FolderId?>>()
    }

    private fun fakeNotes(): NoteRepository =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(NoteRepository::class.java)) { _, method, _ ->
            when (method.name.substringBefore('-')) {
                "observeNotes" -> flowOf(emptyList<Note>())
                "observeNoteCounts" -> flowOf(NoteCounts(0, 0, 0, 0))
                "purgeExpiredTrash" -> Unit
                else -> throw UnsupportedOperationException(method.name)
            }
        } as NoteRepository

    private fun fakeFolders(calls: CreateCalls) = object : FolderRepository {
        override fun observeFolders(): Flow<List<Folder>> = flowOf(emptyList())
        override fun observeTrashedFolders(): Flow<List<Folder>> = flowOf(emptyList())
        override suspend fun createFolder(name: String, parentId: FolderId?, color: Long?): FolderId {
            calls.created += Triple(name, color, parentId)
            return FolderId("new")
        }
        override suspend fun rename(id: FolderId, name: String) = Unit
        override suspend fun setColor(id: FolderId, color: Long?) = Unit
        override suspend fun trash(ids: List<FolderId>, token: org.samesame.notes.core.security.LockToken?) = Unit
        override suspend fun restore(ids: List<FolderId>) = Unit
        override suspend fun deletePermanently(ids: List<FolderId>, token: org.samesame.notes.core.security.LockToken?) = Unit
        override suspend fun lockedNoteIdsIn(ids: List<FolderId>): List<org.samesame.notes.core.model.NoteId> = emptyList()
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

    @Test
    fun createFolderUnderPassesTheSelectedFolderAsParent() = runTest(dispatcher) {
        val calls = CreateCalls()
        val vm = NotesViewModel(fakeNotes(), fakeFolders(calls), fakePrefs, androidx.lifecycle.SavedStateHandle())

        vm.createFolderUnder("Child", 123L, FolderId("parent"))
        advanceUntilIdle()

        assertThat(calls.created).hasSize(1)
        assertThat(calls.created[0].first).isEqualTo("Child")
        assertThat(calls.created[0].second).isEqualTo(123L)
        assertThat(calls.created[0].third).isEqualTo(FolderId("parent"))
    }

    @Test
    fun createFolderUnderWithNullParentStaysTopLevel() = runTest(dispatcher) {
        val calls = CreateCalls()
        val vm = NotesViewModel(fakeNotes(), fakeFolders(calls), fakePrefs, androidx.lifecycle.SavedStateHandle())

        vm.createFolderUnder("Top", null, null)
        advanceUntilIdle()

        assertThat(calls.created.single().third).isNull()
    }

    @Test
    fun createFolderUnderDoesNotThrowWhenTheRepositoryRefuses() = runTest(dispatcher) {
        val refusing = object : FolderRepository {
            override fun observeFolders(): Flow<List<Folder>> = flowOf(emptyList())
            override fun observeTrashedFolders(): Flow<List<Folder>> = flowOf(emptyList())
            override suspend fun createFolder(name: String, parentId: FolderId?, color: Long?): FolderId {
                throw IllegalArgumentException("Folders can nest ${Limits.MAX_FOLDER_DEPTH} levels deep")
            }
            override suspend fun rename(id: FolderId, name: String) = Unit
            override suspend fun setColor(id: FolderId, color: Long?) = Unit
            override suspend fun trash(ids: List<FolderId>, token: org.samesame.notes.core.security.LockToken?) = Unit
            override suspend fun restore(ids: List<FolderId>) = Unit
            override suspend fun deletePermanently(ids: List<FolderId>, token: org.samesame.notes.core.security.LockToken?) = Unit
            override suspend fun lockedNoteIdsIn(ids: List<FolderId>): List<org.samesame.notes.core.model.NoteId> = emptyList()
            override suspend fun purgeExpiredTrash() = Unit
        }
        val vm = NotesViewModel(fakeNotes(), refusing, fakePrefs, androidx.lifecycle.SavedStateHandle())

        vm.createFolderUnder("Too deep", null, FolderId("deep"))
        advanceUntilIdle()
        // No exception escaped; the ViewModel swallows the refusal like createFolder does.
        assertThat(vm.uiState.value.folders).isEmpty()
    }
}

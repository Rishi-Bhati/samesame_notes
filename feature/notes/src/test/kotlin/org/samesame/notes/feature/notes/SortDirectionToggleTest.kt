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
import org.samesame.notes.core.data.repository.FolderRepository
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.data.repository.UserPreferencesRepository
import org.samesame.notes.core.model.Folder
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.NoteCounts
import org.samesame.notes.core.model.SortDirection
import org.samesame.notes.core.model.SortField
import org.samesame.notes.core.model.UserPreferences
import org.samesame.notes.core.model.ViewMode
import java.lang.reflect.Proxy

/** The sort-direction arrow (H1): a tap flips the stored direction and the shown state, twice flips it back. */
@OptIn(ExperimentalCoroutinesApi::class)
class SortDirectionToggleTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val notes: NoteRepository =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(NoteRepository::class.java)) { _, method, _ ->
            when (method.name.substringBefore('-')) {
                "observeNotes" -> flowOf(emptyList<org.samesame.notes.core.model.Note>())
                "observeNoteCounts" -> flowOf(NoteCounts(0, 0, 0, 0))
                "purgeExpiredTrash" -> Unit
                else -> throw UnsupportedOperationException(method.name)
            }
        } as NoteRepository

    private val folders = object : FolderRepository {
        override fun observeFolders(): Flow<List<Folder>> = flowOf(emptyList())
        override fun observeTrashedFolders(): Flow<List<Folder>> = flowOf(emptyList())
        override suspend fun createFolder(name: String, parentId: FolderId?, color: Long?) = FolderId("new")
        override suspend fun rename(id: FolderId, name: String) = Unit
        override suspend fun setColor(id: FolderId, color: Long?) = Unit
        override suspend fun trash(ids: List<FolderId>, token: org.samesame.notes.core.security.LockToken?) = Unit
        override suspend fun restore(ids: List<FolderId>) = Unit
        override suspend fun deletePermanently(ids: List<FolderId>, token: org.samesame.notes.core.security.LockToken?) = Unit
        override suspend fun lockedNoteIdsIn(ids: List<FolderId>): List<org.samesame.notes.core.model.NoteId> = emptyList()
        override suspend fun purgeExpiredTrash() = Unit
    }

    /** Behaves like the real store: a write is emitted back through [preferences]. */
    private val prefs = object : UserPreferencesRepository {
        override val preferences = MutableStateFlow(UserPreferences())
        override suspend fun setViewMode(mode: ViewMode) { preferences.value = preferences.value.copy(viewMode = mode) }
        override suspend fun setSortField(field: SortField) { preferences.value = preferences.value.copy(sortField = field) }
        override suspend fun setSortDirection(direction: SortDirection) { preferences.value = preferences.value.copy(sortDirection = direction) }
        override suspend fun setPinFavoritesToTop(pin: Boolean) { preferences.value = preferences.value.copy(pinFavoritesToTop = pin) }
        override suspend fun setShowFolderCards(show: Boolean) { preferences.value = preferences.value.copy(showFolderCards = show) }
    }

    @Test
    fun eachTapFlipsTheDirection() = runTest(dispatcher) {
        val vm = NotesViewModel(notes, folders, prefs, androidx.lifecycle.SavedStateHandle())
        val job = backgroundScope.launch(dispatcher) { vm.uiState.collect {} }
        advanceUntilIdle()
        assertThat(vm.uiState.value.preferences.sortDirection).isEqualTo(SortDirection.Descending)

        vm.toggleSortDirection()
        advanceUntilIdle()
        assertThat(vm.uiState.value.preferences.sortDirection).isEqualTo(SortDirection.Ascending)

        vm.toggleSortDirection()
        advanceUntilIdle()
        assertThat(vm.uiState.value.preferences.sortDirection).isEqualTo(SortDirection.Descending)
        job.cancel()
    }
}

package org.samesame.notes.feature.notes

import androidx.lifecycle.SavedStateHandle
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
import org.samesame.notes.core.data.repository.AppSettingsRepository
import org.samesame.notes.core.data.repository.FolderRepository
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.data.repository.UserPreferencesRepository
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.Folder
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteCounts
import org.samesame.notes.core.model.NoteFilter
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.SortDirection
import org.samesame.notes.core.model.SortField
import org.samesame.notes.core.model.UserPreferences
import org.samesame.notes.core.model.ViewMode
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class HideLockedNotesTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun note(id: String, locked: Boolean = false) = Note(
        id = NoteId(id), title = id, previewText = "", folderId = null, isFavorite = false, isLocked = locked,
        pageMode = PageMode.Pages, backgroundColor = 0, createdAt = 0, modifiedAt = 0, lastOpenedAt = 0, trashedAt = null,
    )

    private fun fakeNotes(all: List<Note>): NoteRepository =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(NoteRepository::class.java)) { _, method, args ->
            when (method.name.substringBefore('-')) {
                "observeNotes" -> {
                    val filter = args[0] as NoteFilter
                    if (filter == NoteFilter.Locked) flowOf(all.filter { it.isLocked }) else flowOf(all)
                }
                "observeNoteCounts" -> flowOf(NoteCounts(all.size, all.size, 0, all.count { it.isLocked }))
                "purgeExpiredTrash" -> Unit
                else -> throw UnsupportedOperationException(method.name)
            }
        } as NoteRepository

    private val fakeFolders = object : FolderRepository {
        override fun observeFolders(): Flow<List<Folder>> = flowOf(emptyList())
        override fun observeTrashedFolders(): Flow<List<Folder>> = flowOf(emptyList())
        override suspend fun createFolder(name: String, parentId: FolderId?, color: Long?) = FolderId("new")
        override suspend fun rename(id: FolderId, name: String) = Unit
        override suspend fun setColor(id: FolderId, color: Long?) = Unit
        override suspend fun trash(ids: List<FolderId>, token: org.samesame.notes.core.security.LockToken?) = Unit
        override suspend fun restore(ids: List<FolderId>) = Unit
        override suspend fun deletePermanently(ids: List<FolderId>, token: org.samesame.notes.core.security.LockToken?) = Unit
        override suspend fun lockedNoteIdsIn(ids: List<FolderId>): List<NoteId> = emptyList()
        override suspend fun purgeExpiredTrash() = Unit
    }

    private val fakePrefs = object : UserPreferencesRepository {
        override val preferences = MutableStateFlow(UserPreferences())
        override suspend fun setViewMode(mode: ViewMode) = Unit
        override suspend fun setSortField(field: SortField) = Unit
        override suspend fun setSortDirection(direction: SortDirection) = Unit
        override suspend fun setPinFavoritesToTop(pin: Boolean) = Unit
        override suspend fun setShowFolderCards(show: Boolean) = Unit
    }

    private fun fakeSettings(hideLockedNotes: Boolean) = object : AppSettingsRepository {
        override val settings = MutableStateFlow(AppSettings(hideLockedNotes = hideLockedNotes))
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    @Test
    fun lockedNotesShownInAllNotesWhenSettingIsOff() = runTest(dispatcher) {
        val notes = listOf(note("unlocked"), note("secret", locked = true))
        val vm = NotesViewModel(fakeNotes(notes), fakeFolders, fakePrefs, SavedStateHandle(), fakeSettings(false))
        val job = backgroundScope.launch(dispatcher) { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.notes.map { it.id.value }).containsExactly("unlocked", "secret")
        job.cancel()
    }

    @Test
    fun lockedNotesHiddenFromAllNotesWhenSettingIsOn() = runTest(dispatcher) {
        val notes = listOf(note("unlocked"), note("secret", locked = true))
        val vm = NotesViewModel(fakeNotes(notes), fakeFolders, fakePrefs, SavedStateHandle(), fakeSettings(true))
        val job = backgroundScope.launch(dispatcher) { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.notes.map { it.id.value }).containsExactly("unlocked")
        job.cancel()
    }

    @Test
    fun lockedNotesStillVisibleInLockedFilterWhenSettingIsOn() = runTest(dispatcher) {
        val notes = listOf(note("unlocked"), note("secret", locked = true))
        val vm = NotesViewModel(fakeNotes(notes), fakeFolders, fakePrefs, SavedStateHandle(), fakeSettings(true))
        val job = backgroundScope.launch(dispatcher) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.selectFilter(NoteFilter.Locked)
        advanceUntilIdle()

        assertThat(vm.uiState.value.notes.map { it.id.value }).containsExactly("secret")
        job.cancel()
    }
}

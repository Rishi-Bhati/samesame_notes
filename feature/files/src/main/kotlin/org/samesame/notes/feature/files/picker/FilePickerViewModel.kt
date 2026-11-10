package org.samesame.notes.feature.files.picker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.samesame.notes.core.data.repository.FilesRepository
import org.samesame.notes.core.model.files.FileFolder
import org.samesame.notes.core.model.files.FileFolderId
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.feature.files.FilesPreferences
import org.samesame.notes.feature.files.FilesViewModel
import android.net.Uri
import javax.inject.Inject

data class FilePickerUiState(
    val loaded: Boolean = false,
    /** The open folder; null = Files root. */
    val folderId: FileFolderId? = null,
    val path: List<FileFolder> = emptyList(),
    /** Folders of the open folder; empty while searching. */
    val subFolders: List<FileFolder> = emptyList(),
    /** The open folder's files, or the search results while [searching]. Never files in the Recycle bin. */
    val files: List<LibraryFile> = emptyList(),
    val allFolders: List<FileFolder> = emptyList(),
    /** Null = not searching; "" = the search box is open and empty. */
    val query: String? = null,
    /** The picked files in the order they were picked, across folders and searches. */
    val selected: List<FileId> = emptyList(),
) {
    val searching: Boolean get() = query != null
    val title: String? get() = path.lastOrNull()?.name
    val isEmpty: Boolean get() = subFolders.isEmpty() && files.isEmpty()
}

/**
 * State of the "pick from Files" sheet: browse the folder tree, search by name, pick several files. The selection
 * survives moving between folders and searches, and is returned in the order it was made.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FilePickerViewModel @Inject constructor(
    private val repo: FilesRepository,
    private val prefs: FilesPreferences,
) : ViewModel() {

    private data class Local(val folderId: FileFolderId?, val query: String?, val selected: List<FileId>)

    private val local = MutableStateFlow(Local(null, null, emptyList()))

    val uiState: StateFlow<FilePickerUiState> = combine(
        local.flatMapLatest { l ->
            val q = l.query?.trim().orEmpty()
            when {
                l.query == null -> repo.observeFiles(l.folderId, prefs.sort)
                q.isEmpty() -> kotlinx.coroutines.flow.flowOf(emptyList())
                else -> repo.search(q)
            }
        },
        repo.observeFolders(),
        local,
    ) { files, all, l ->
        val open = l.folderId?.takeIf { id -> all.any { it.id == id } }
        FilePickerUiState(
            loaded = true,
            folderId = open,
            path = open?.let { FilesViewModel.chain(it, all) }.orEmpty(),
            subFolders = if (l.query != null) emptyList() else all.filter { it.parentId == open },
            files = (if (l.query == null && open != l.folderId) emptyList() else files).filter { !it.isInTrash },
            allFolders = all,
            query = l.query,
            selected = l.selected,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilePickerUiState())

    init {
        // The open folder went to the Recycle bin meanwhile: back to the root.
        viewModelScope.launch {
            repo.observeFolders().collect { all ->
                val open = local.value.folderId
                if (open != null && all.none { it.id == open }) local.update { it.copy(folderId = null) }
            }
        }
    }

    /** A newly shown sheet starts at the root with nothing picked. */
    fun reset() { local.value = Local(null, null, emptyList()) }

    fun openFolder(id: FileFolderId?) = local.update { it.copy(folderId = id, query = null) }

    fun startSearch() = local.update { if (it.query == null) it.copy(query = "") else it }
    fun setQuery(query: String) = local.update { it.copy(query = query) }

    fun toggle(file: LibraryFile) {
        if (file.isInTrash) return
        local.update { it.copy(selected = if (file.id in it.selected) it.selected - file.id else it.selected + file.id) }
    }

    /** Back: leaves the search, then goes up one folder; false at the root (the sheet then closes). */
    fun navigateUp(): Boolean {
        val l = local.value
        if (l.query != null) { local.value = l.copy(query = null); return true }
        val open = uiState.value
        if (open.folderId == null) return false
        local.value = l.copy(folderId = open.path.dropLast(1).lastOrNull()?.id)
        return true
    }

    /** The picked ids that still are live files, in pick order. */
    suspend fun confirmed(): List<FileId> {
        val picked = local.value.selected
        val live = repo.getFiles(picked).filter { !it.isInTrash }.map { it.id }.toSet()
        return picked.filter { it in live }
    }

    fun contentUri(file: LibraryFile): Uri = repo.contentUri(file)
}

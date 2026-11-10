package org.samesame.notes.feature.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.samesame.notes.core.data.repository.FilesRepository
import org.samesame.notes.core.model.files.DuplicatePolicy
import org.samesame.notes.core.model.files.FileFolder
import org.samesame.notes.core.model.files.FileFolderId
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.FileLinks
import org.samesame.notes.core.model.files.FileSort
import org.samesame.notes.core.model.files.FileSortField
import org.samesame.notes.core.model.files.FileUsage
import org.samesame.notes.core.model.files.FilesSummary
import org.samesame.notes.core.model.files.FilesTrash
import org.samesame.notes.core.model.files.ImportResult
import org.samesame.notes.core.model.files.LibraryFile
import javax.inject.Inject

/** What is selected on the Files screen. Files and folders can be mixed (Delete and Select all); Move and Share use the files. */
data class FilesSelection(val active: Boolean = false, val files: Set<FileId> = emptySet(), val folders: Set<FileFolderId> = emptySet()) {
    val count: Int get() = files.size + folders.size
}

data class FilesUiState(
    val loaded: Boolean = false,
    /** The open folder; null = Files root. */
    val folderId: FileFolderId? = null,
    /** Folder chain of [folderId] from the top level. */
    val path: List<FileFolder> = emptyList(),
    val subFolders: List<FileFolder> = emptyList(),
    val files: List<LibraryFile> = emptyList(),
    /** Every folder (the Move picker and search paths). */
    val allFolders: List<FileFolder> = emptyList(),
    val sort: FileSort = FileSort(),
    val viewMode: FilesViewMode = FilesViewMode.Grid,
    val selection: FilesSelection = FilesSelection(),
    val summary: FilesSummary = FilesSummary(),
    /** A file the screen was asked to show ("Open in Files"): it is selected, and the list scrolls to it once. */
    val reveal: FileId? = null,
) {
    val selecting: Boolean get() = selection.active
    val isEmpty: Boolean get() = subFolders.isEmpty() && files.isEmpty()
    val selectedFiles: List<LibraryFile> get() = files.filter { it.id in selection.files }
    val selectedFolders: List<FileFolder> get() = subFolders.filter { it.id in selection.folders }
    val allSelected: Boolean get() = !isEmpty && selection.files.size == files.size && selection.folders.size == subFolders.size
    val title: String? get() = path.lastOrNull()?.name
}

/** One-shot messages for the snackbar. The UI maps them to strings. */
sealed interface FilesEvent {
    data class Imported(val count: Int, val skipped: Int) : FilesEvent
    data class ImportFailed(val failures: List<ImportFailure>, val ignored: Int) : FilesEvent
    data class Trashed(val count: Int) : FilesEvent
    data class Restored(val count: Int) : FilesEvent
    data class Moved(val count: Int) : FilesEvent
    data class LinkCopied(val count: Int) : FilesEvent
    data class Failure(val kind: FailureKind) : FilesEvent
}

enum class FailureKind { FolderName, TooDeep, Rename, Generic, NotFound }

/** What the Move picker is about to do. */
data class FilesImportUi(val progress: ImportProgress?, val duplicates: List<ImportResult.Duplicate> = emptyList())

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val repo: FilesRepository,
    private val prefs: FilesPreferences,
) : ViewModel() {

    private data class Local(val folderId: FileFolderId?, val sort: FileSort, val viewMode: FilesViewMode, val selection: FilesSelection, val reveal: FileId?)

    private val currentFolder = MutableStateFlow<FileFolderId?>(null)
    private val sort = MutableStateFlow(prefs.sort)
    private val viewMode = MutableStateFlow(prefs.viewMode)
    private val selection = MutableStateFlow(FilesSelection())
    private val reveal = MutableStateFlow<FileId?>(null)

    private val folders: Flow<List<FileFolder>> = repo.observeFolders()

    val uiState: StateFlow<FilesUiState> = combine(
        combine(currentFolder, sort) { f, s -> f to s }.flatMapLatest { (f, s) -> repo.observeFiles(f, s) },
        folders,
        repo.observeSummary(),
        combine(currentFolder, sort, viewMode, selection, reveal, ::Local),
    ) { files, all, summary, local ->
        val open = local.folderId?.takeIf { id -> all.any { it.id == id } }
        FilesUiState(
            loaded = true,
            folderId = open,
            path = open?.let { id -> chain(id, all) }.orEmpty(),
            subFolders = all.filter { it.parentId == open },
            files = if (open == local.folderId) files else emptyList(),
            allFolders = all,
            sort = local.sort,
            viewMode = local.viewMode,
            selection = local.selection.retain(files, all),
            summary = summary,
            reveal = local.reveal,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilesUiState())

    val trash: StateFlow<FilesTrash> = repo.observeTrash()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilesTrash(emptyList(), emptyList()))

    private val eventChannel = Channel<FilesEvent>(Channel.BUFFERED)
    val events: Flow<FilesEvent> = eventChannel.consumeAsFlow()

    private val _import = MutableStateFlow<FilesImportUi?>(null)
    val importUi: StateFlow<FilesImportUi?> = _import
    private var importJob: Job? = null

    init {
        viewModelScope.launch { repo.purgeExpiredTrash() }
        // A folder that went to the Recycle bin (or was removed) while it was open: back to the root.
        viewModelScope.launch {
            folders.collect { all ->
                val open = currentFolder.value
                if (open != null && all.none { it.id == open }) currentFolder.value = null
            }
        }
    }

    // ---- Navigation ----

    fun openFolder(id: FileFolderId?) {
        selection.value = FilesSelection()
        currentFolder.value = id
    }

    /** Up one level; false at the root (the caller then leaves the screen). */
    fun navigateUp(): Boolean {
        val open = uiState.value
        if (open.folderId == null) return false
        openFolder(open.path.dropLast(1).lastOrNull()?.id)
        return true
    }

    /**
     * "Open in Files": opens the folder of [id] with the file selected, so it stands out and its actions are at hand.
     * A file that is gone or in the Recycle bin is reported instead.
     */
    fun reveal(id: FileId) {
        viewModelScope.launch {
            val file = repo.getFile(id)
            if (file == null || file.isInTrash) { fail(FailureKind.NotFound); return@launch }
            currentFolder.value = file.folderId
            selection.value = FilesSelection(active = true, files = setOf(file.id))
            reveal.value = file.id
        }
    }

    /** The list has scrolled to the revealed file. */
    fun revealShown() { reveal.value = null }

    // ---- Selection ----

    fun startSelecting(file: LibraryFile? = null, folder: FileFolder? = null) {
        selection.value = FilesSelection(
            active = true,
            files = setOfNotNull(file?.id),
            folders = setOfNotNull(folder?.id),
        )
    }

    fun toggle(file: LibraryFile) = selection.update { it.copy(files = it.files.toggled(file.id)) }
    fun toggle(folder: FileFolder) = selection.update { it.copy(folders = it.folders.toggled(folder.id)) }
    fun clearSelection() { selection.value = FilesSelection() }

    fun toggleSelectAll() {
        val s = uiState.value
        selection.value = if (s.allSelected) FilesSelection(active = true)
        else FilesSelection(active = true, files = s.files.map { it.id }.toSet(), folders = s.subFolders.map { it.id }.toSet())
    }

    // ---- View ----

    fun setSortField(field: FileSortField) {
        val next = if (sort.value.field == field) sort.value else FileSort(field, ascending = field == FileSortField.Name || field == FileSortField.Type)
        setSort(next)
    }

    fun toggleSortDirection() = setSort(sort.value.copy(ascending = !sort.value.ascending))

    private fun setSort(next: FileSort) { sort.value = next; prefs.sort = next }

    fun setViewMode(mode: FilesViewMode) { viewMode.value = mode; prefs.viewMode = mode }

    // ---- Folders ----

    fun createFolder(name: String, color: Long?, parent: FileFolderId? = currentFolder.value) {
        viewModelScope.launch {
            try { repo.createFolder(name, parent, color) } catch (e: IllegalArgumentException) { fail(FailureKind.FolderName) }
        }
    }

    suspend fun createFolderNow(name: String, color: Long?, parent: FileFolderId?): FileFolderId? = try {
        repo.createFolder(name, parent, color)
    } catch (e: IllegalArgumentException) {
        fail(FailureKind.FolderName); null
    }

    fun renameFolder(id: FileFolderId, name: String) {
        viewModelScope.launch {
            try { repo.renameFolder(id, name); clearSelection() } catch (e: IllegalArgumentException) { fail(FailureKind.FolderName) }
        }
    }

    // ---- Files ----

    fun rename(id: FileId, name: String) {
        viewModelScope.launch {
            try { repo.rename(id, name); clearSelection() } catch (e: IllegalArgumentException) { fail(FailureKind.Rename) }
        }
    }

    fun toggleFavorite(files: List<LibraryFile>) {
        if (files.isEmpty()) return
        val make = files.any { !it.isFavorite }
        viewModelScope.launch { repo.setFavorite(files.map { it.id }, make); clearSelection() }
    }

    /** Moves the selected files and folders into [target] (null = root). A folder can't go into itself or its own subtree. */
    fun moveSelection(target: FileFolderId?) {
        val s = uiState.value
        val files = s.selection.files.toList()
        val blocked = blockedTargets(s.selection.folders, s.allFolders)
        if (target in blocked) { viewModelScope.launch { fail(FailureKind.TooDeep) }; return }
        viewModelScope.launch {
            var moved = 0
            try {
                if (files.isNotEmpty()) { repo.moveFiles(files, target); moved += files.size }
                s.selection.folders.forEach { repo.moveFolder(it, target); moved++ }
                eventChannel.send(FilesEvent.Moved(moved))
            } catch (e: IllegalArgumentException) {
                fail(FailureKind.TooDeep)
            }
            clearSelection()
        }
    }

    fun trashSelection() {
        val s = uiState.value.selection
        if (s.count == 0) return
        viewModelScope.launch {
            repo.trash(s.files.toList(), s.folders.toList())
            eventChannel.send(FilesEvent.Trashed(s.count))
            clearSelection()
        }
    }

    // ---- Links, sharing ----

    /** The text Copy link puts on the clipboard: one link per line. */
    fun linkText(files: List<LibraryFile>): String = files.joinToString("\n") { FileLinks.format(it) }

    fun linkCopied(count: Int) { eventChannel.trySend(FilesEvent.LinkCopied(count)) }

    fun contentUri(file: LibraryFile): Uri = repo.contentUri(file)

    fun search(query: String): Flow<List<LibraryFile>> = repo.search(query)

    fun usage(id: FileId): Flow<List<FileUsage>> = repo.observeUsage(id)

    suspend fun usageCount(files: List<FileId>): Int = repo.usageCount(files)

    suspend fun folderPath(id: FileFolderId?): List<FileFolder> = id?.let { repo.folderPath(it) }.orEmpty()

    // ---- Recycle bin ----

    fun restore(files: Set<FileId>, folders: Set<FileFolderId>) {
        viewModelScope.launch {
            repo.restore(files.toList(), folders.toList())
            eventChannel.send(FilesEvent.Restored(files.size + folders.size))
        }
    }

    fun deletePermanently(files: Set<FileId>, folders: Set<FileFolderId>) {
        viewModelScope.launch { repo.deletePermanently(files.toList(), folders.toList()) }
    }

    fun emptyTrash() { viewModelScope.launch { repo.emptyTrash() } }

    // ---- Upload ----

    /** Copies the picked documents into the open folder, with progress, cancel and the duplicate question. */
    fun upload(uris: List<Uri>) {
        if (uris.isEmpty() || importJob?.isActive == true) return
        val folder = currentFolder.value
        importJob = viewModelScope.launch {
            _import.value = FilesImportUi(progress = ImportProgress(1, uris.size, ""))
            try {
                val report = FilesImporter.run(
                    items = uris,
                    nameOf = { it.lastPathSegment.orEmpty() },
                    import = { uri, policy, onProgress -> repo.importFromUri(uri, folder, onDuplicate = policy, onProgress = onProgress) },
                    onProgress = { p -> _import.update { FilesImportUi(p, it?.duplicates.orEmpty()) } },
                )
                finishImport(report)
            } finally {
                if (_import.value?.duplicates.isNullOrEmpty()) _import.value = null
            }
        }
    }

    private suspend fun finishImport(report: ImportReport) {
        if (report.imported.isNotEmpty() || report.skipped > 0) eventChannel.send(FilesEvent.Imported(report.imported.size, report.skipped))
        if (report.failures.isNotEmpty() || report.ignored > 0) eventChannel.send(FilesEvent.ImportFailed(report.failures, report.ignored))
        _import.value = if (report.duplicates.isEmpty()) null else FilesImportUi(null, report.duplicates)
    }

    fun cancelImport() { importJob?.cancel(); importJob = null; _import.value = _import.value?.copy(progress = null)?.takeIf { it.duplicates.isNotEmpty() } }

    /** Answers the first waiting "Already in this folder" question. */
    fun resolveDuplicate(keepBoth: Boolean) {
        val waiting = _import.value?.duplicates.orEmpty()
        val first = waiting.firstOrNull() ?: return
        _import.value = waiting.drop(1).takeIf { it.isNotEmpty() }?.let { FilesImportUi(null, it) }
        viewModelScope.launch {
            val added = repo.resolveDuplicate(first.pending, keepBoth)
            if (added != null) eventChannel.send(FilesEvent.Imported(1, 0))
        }
    }

    private suspend fun fail(kind: FailureKind) { eventChannel.send(FilesEvent.Failure(kind)) }

    private fun <T> Set<T>.toggled(item: T): Set<T> = if (item in this) this - item else this + item

    private fun FilesSelection.retain(files: List<LibraryFile>, all: List<FileFolder>): FilesSelection =
        if (!active) this else copy(
            files = this.files.filterTo(HashSet()) { id -> files.any { it.id == id } },
            folders = this.folders.filterTo(HashSet()) { id -> all.any { it.id == id } },
        )

    companion object {
        /** The folder chain from the top level to [id], by parent links. */
        internal fun chain(id: FileFolderId, all: List<FileFolder>): List<FileFolder> {
            val byId = all.associateBy { it.id }
            val out = ArrayList<FileFolder>()
            var cursor = byId[id]
            var guard = 0
            while (cursor != null && guard++ < 200) { out += cursor; cursor = cursor.parentId?.let(byId::get) }
            return out.asReversed()
        }

        /** Folders a move of [moving] must not target: themselves and everything below them. */
        internal fun blockedTargets(moving: Set<FileFolderId>, all: List<FileFolder>): Set<FileFolderId> {
            if (moving.isEmpty()) return emptySet()
            val blocked = HashSet(moving)
            var grew = true
            while (grew) {
                grew = false
                all.forEach { f -> if (f.parentId in blocked && blocked.add(f.id)) grew = true }
            }
            return blocked
        }
    }
}

package org.samesame.notes.feature.files.viewer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.samesame.notes.core.data.repository.FilesRepository
import org.samesame.notes.core.data.transfer.ImportResult
import org.samesame.notes.core.data.transfer.ImportSource
import org.samesame.notes.core.data.transfer.NoteImportService
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.files.FileFolder
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.FileLinks
import org.samesame.notes.core.model.files.FileUsage
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.feature.files.FilesPreferences
import java.io.InputStream
import javax.inject.Inject

sealed interface ViewerState {
    data object Loading : ViewerState
    /** Unknown id, or the file was deleted for good. */
    data object Missing : ViewerState
    data class Trashed(val file: LibraryFile) : ViewerState
    /**
     * [file] is the file on screen. For [ViewerKind.Image], [images] are the viewable images of the folder the viewer was
     * opened in (the Files screen's order) and [file] follows the page being shown.
     */
    data class Ready(
        val file: LibraryFile,
        val kind: ViewerKind,
        val images: List<LibraryFile> = emptyList(),
        val folders: List<FileFolder> = emptyList(),
    ) : ViewerState {
        val imageIndex: Int get() = images.indexOfFirst { it.id == file.id }
    }
}

sealed interface ViewerEvent {
    /** The file went to the Recycle bin from the viewer: close it. */
    data object Deleted : ViewerEvent
    data object RenameFailed : ViewerEvent
    data class NoteCreated(val noteId: NoteId) : ViewerEvent
    data object NoteFailed : ViewerEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FileViewerViewModel @Inject constructor(
    private val repo: FilesRepository,
    private val prefs: FilesPreferences,
    private val noteImporter: Lazy<NoteImportService>,
) : ViewModel() {

    private val current = MutableStateFlow<FileId?>(null)
    /** The file the viewer was opened with; its folder supplies the images to swipe through. */
    private val opened = MutableStateFlow<FileId?>(null)
    private var deleting = false

    private val siblings: Flow<List<LibraryFile>> = opened.filterNotNull()
        .flatMapLatest { id -> repo.observeFile(id).map { it?.takeIf { f -> !f.isInTrash } } }
        // Keyed on the folder only, so renames of the opened file don't restart the listing.
        .map { f -> f?.let { Anchor(it.folderId, true) } ?: Anchor(null, false) }
        .distinctUntilChanged()
        .flatMapLatest { a ->
            if (!a.known) flowOf(emptyList())
            else repo.observeFiles(a.folderId, prefs.sort).map { list -> list.filter { ViewerKind.of(it) == ViewerKind.Image } }
        }

    private data class Anchor(val folderId: org.samesame.notes.core.model.files.FileFolderId?, val known: Boolean)

    val uiState: StateFlow<ViewerState> = combine(
        current.filterNotNull().flatMapLatest { id -> repo.observeFile(id) },
        siblings,
        repo.observeFolders(),
    ) { file, images, folders ->
        when {
            file == null -> ViewerState.Missing
            file.isInTrash -> ViewerState.Trashed(file)
            else -> {
                val kind = ViewerKind.of(file)
                // The opened image always belongs to the set, even when the folder listing lags behind.
                val set = if (kind != ViewerKind.Image) emptyList() else if (images.any { it.id == file.id }) images else listOf(file)
                ViewerState.Ready(file, kind, set, folders)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ViewerState.Loading)

    private val eventChannel = Channel<ViewerEvent>(Channel.BUFFERED)
    val events: Flow<ViewerEvent> = eventChannel.receiveAsFlow()

    private val _busy = MutableStateFlow(false)
    /** True while "Annotate as note" builds the note. */
    val busy: StateFlow<Boolean> = _busy

    /** Called once with the id to open; a recreated host keeps the page it was on. */
    fun start(id: FileId) {
        if (opened.value != null) return
        opened.value = id
        current.value = id
    }

    /** The image pager settled on another image of the set. */
    fun showImage(id: FileId) {
        val state = uiState.value as? ViewerState.Ready ?: return
        if (state.images.any { it.id == id }) current.value = id
    }

    fun contentUri(file: LibraryFile): Uri = repo.contentUri(file)

    fun linkText(file: LibraryFile): String = FileLinks.format(file)

    fun usage(id: FileId): Flow<List<FileUsage>> = repo.observeUsage(id)

    suspend fun usageCount(id: FileId): Int = repo.usageCount(listOf(id))

    fun rename(id: FileId, name: String) {
        viewModelScope.launch {
            try { repo.rename(id, name) } catch (e: IllegalArgumentException) { eventChannel.send(ViewerEvent.RenameFailed) }
        }
    }

    /** Moves the file on screen to the Files Recycle bin and closes the viewer. */
    fun delete(id: FileId) {
        if (deleting) return
        deleting = true
        viewModelScope.launch {
            repo.trash(listOf(id))
            eventChannel.send(ViewerEvent.Deleted)
        }
    }

    fun restore(id: FileId) { viewModelScope.launch { repo.restore(listOf(id)) } }

    /** Remembers a page count or duration the viewer learned, for the Files list. */
    fun learned(file: LibraryFile, pageCount: Int? = null, durationMs: Long? = null) {
        if ((pageCount == null || pageCount == file.pageCount) && (durationMs == null || durationMs == file.durationMs)) return
        viewModelScope.launch { runCatching { repo.updateMetadata(file.id, pageCount = pageCount, durationMs = durationMs) } }
    }

    /** "Annotate as note": makes a note from the PDF through the existing PDF import. [open] reads the stored copy. */
    fun annotateAsNote(file: LibraryFile, open: () -> InputStream) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val result = runCatching { noteImporter.get().import(ImportSource(file.displayName, file.mimeType, file.displayName, open)) }.getOrNull()
                eventChannel.send(if (result is ImportResult.Imported) ViewerEvent.NoteCreated(result.id) else ViewerEvent.NoteFailed)
            } finally {
                _busy.value = false
            }
        }
    }
}

package org.samesame.notes.feature.notes

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.repository.FolderRepository
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.data.repository.UserPreferencesRepository
import org.samesame.notes.core.model.Folder
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteFilter
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.SortDirection
import org.samesame.notes.core.model.SortField
import org.samesame.notes.core.model.SortOrder
import org.samesame.notes.core.model.UserPreferences
import org.samesame.notes.core.model.ViewMode
import javax.inject.Inject

data class NotesUiState(
    val filter: NoteFilter = NoteFilter.All,
    val notes: List<Note> = emptyList(),
    /** All folders (flat); use [visibleFolders] for the ones to show as cards in the current view. */
    val folders: List<Folder> = emptyList(),
    val preferences: UserPreferences = UserPreferences(),
    val selecting: Boolean = false,
    val selection: Set<NoteId> = emptySet(),
    /** Folders picked in selection mode (folder cards in the list, or rows in Manage folders). */
    val folderSelection: Set<FolderId> = emptySet(),
    /** Folders in the Trash; use [trashFolders] for the topmost ones the list shows as cards. */
    val trashedFolders: List<Folder> = emptyList(),
    val allNotesCount: Int = 0,
    val rootNotesCount: Int = 0,
    val loaded: Boolean = false,
) {
    val isTrash get() = filter == NoteFilter.Trash
    val isFoldersRoot get() = filter == NoteFilter.Uncategorized
    val viewMode get() = preferences.viewMode

    /** Topmost folders in the Trash: the cards the Recycle bin shows before the notes. */
    val trashFolders: List<Folder>
        get() {
            if (!isTrash) return emptyList()
            val trashedIds = trashedFolders.mapTo(HashSet()) { it.id }
            return trashedFolders.filter { it.parentId == null || it.parentId !in trashedIds }
        }

    /** Everything selection mode can pick in the current view. */
    val selectableCount: Int get() = notes.size + visibleFolders.size + trashFolders.size

    val selectedCount: Int get() = selection.size + folderSelection.size

    val allSelected: Boolean
        get() = selectableCount > 0 &&
            selection.size == notes.size && folderSelection.size == visibleFolders.size + trashFolders.size

    /**
     * Folder cards shown above the notes: top-level folders in Folders, children in a folder, top-level folders
     * in All notes. The All notes and in-folder cards are hidden unless [UserPreferences.showFolderCards] is on;
     * the Folders tab always shows them (it is the folder browser).
     */
    val visibleFolders: List<Folder>
        get() = when (val f = filter) {
            NoteFilter.All -> if (preferences.showFolderCards) folders.filter { it.parentId == null } else emptyList()
            NoteFilter.Uncategorized -> folders.filter { it.parentId == null }
            is NoteFilter.InFolder -> if (preferences.showFolderCards) folders.filter { it.parentId == f.folderId } else emptyList()
            else -> emptyList()
        }
}

/** What the list header shows under the title: nothing, "M notes" or "N folders, M notes" (folder cards shown). */
internal sealed interface ListSubtitle {
    data object None : ListSubtitle
    data class Notes(val notes: Int) : ListSubtitle
    data class FoldersAndNotes(val folders: Int, val notes: Int) : ListSubtitle
}

/** The folder ids [NotesUiState.visibleFolders] shows for a live filter. */
private fun visibleFolderIds(filter: NoteFilter, folders: List<Folder>, showFolderCards: Boolean): Set<FolderId> =
    when (filter) {
        NoteFilter.All -> if (showFolderCards) folders.filter { it.parentId == null } else emptyList()
        NoteFilter.Uncategorized -> folders.filter { it.parentId == null }
        is NoteFilter.InFolder -> if (showFolderCards) folders.filter { it.parentId == filter.folderId } else emptyList()
        else -> emptyList()
    }.mapTo(HashSet()) { it.id }

/** Counts only what the list shows: folder cards are counted only when [NotesUiState.visibleFolders] shows them. */
internal fun listSubtitle(state: NotesUiState): ListSubtitle {
    val folders = state.visibleFolders.size + state.trashFolders.size
    val notes = state.notes.size
    return when {
        notes == 0 && folders == 0 -> ListSubtitle.None
        folders > 0 -> ListSubtitle.FoldersAndNotes(folders, notes)
        else -> ListSubtitle.Notes(notes)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val notes: NoteRepository,
    private val folders: FolderRepository,
    private val preferences: UserPreferencesRepository,
) : ViewModel() {

    private val filter = MutableStateFlow<NoteFilter>(NoteFilter.All)
    private val selecting = MutableStateFlow(false)
    private val selection = MutableStateFlow<Set<NoteId>>(emptySet())
    private val folderSelection = MutableStateFlow<Set<FolderId>>(emptySet())

    private val visibleNotes = combine(filter, preferences.preferences.map { it.sortOrder }) { f, s -> f to s }
        .flatMapLatest { (filter, sort) -> notes.observeNotes(filter, sort) }

    private val folderLists = combine(folders.observeFolders(), folders.observeTrashedFolders(), ::Pair)

    /** One count query for both drawer badges: no full-catalog fetch, mapping or sorting. */
    private val counts = notes.observeNoteCounts()

    private val selectionState = combine(selecting, selection, folderSelection) { selecting, notes, folders ->
        Triple(selecting, notes, folders)
    }
    val uiState: StateFlow<NotesUiState> = combine(
        filter, visibleNotes, folderLists, preferences.preferences, selectionState,
    ) { filter, notes, (folders, trashedFolders), prefs, (selecting, selection, folderSelection) ->
        val trashedIds = trashedFolders.mapTo(HashSet()) { it.id }
        // In the Recycle bin a trashed folder card stands for everything inside it.
        val shownNotes = if (filter == NoteFilter.Trash) notes.filter { it.folderId !in trashedIds } else notes
        val shownTrashedFolders = trashedFolders.filter { it.parentId == null || it.parentId !in trashedIds }
        val visibleFolderIds = if (filter == NoteFilter.Trash) {
            shownTrashedFolders.map { it.id }.toSet()
        } else {
            visibleFolderIds(filter, folders, prefs.showFolderCards)
        }
        NotesUiState(
            filter = filter, notes = shownNotes, folders = folders, preferences = prefs,
            selecting = selecting,
            selection = selection.intersect(shownNotes.map { it.id }.toSet()),
            folderSelection = folderSelection.intersect(visibleFolderIds),
            trashedFolders = trashedFolders,
            loaded = true,
        )
    }.combine(counts) { state, (all, root) -> state.copy(allNotesCount = all, rootNotesCount = root) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

    init {
        viewModelScope.launch {
            notes.purgeExpiredTrash()
            folders.purgeExpiredTrash()
        }
    }

    // --- page previews ---

    private val previews = PreviewCache<PreviewKey, ImageBitmap>(PREVIEW_CACHE_ENTRIES)
    private val previewRequests = PreviewRequests<PreviewKey>()
    private val previewInvalidation = PreviewInvalidation()

    /** Rendered no wider than the widest card needs (see [previewWidthFor]). */
    private val previewWidthPx = previewWidthFor(
        runCatching { android.content.res.Resources.getSystem().displayMetrics.widthPixels }.getOrDefault(0), // 0 in JVM tests
    )

    /** At most two first pages render at a time (one on devices with few cores, so scrolling keeps its CPU); the rest wait for a slot. */
    private val previewSlots = Semaphore(if (Runtime.getRuntime().availableProcessors() <= 4) 1 else 2)

    /** The cached first-page preview for [note] in the current theme, read by the cards; null while it renders. */
    fun previewFor(note: Note, darkTheme: Boolean): ImageBitmap? =
        previews[previewKey(note, darkTheme)]

    /**
     * Asks for [note]'s first-page preview once per revision, theme and lock state. Rendering happens off the
     * main thread, deduplicated while in flight; failures fall back to the text preview and are not retried for
     * the same key. Removing a lock is a new key and clears earlier failures (see [PreviewInvalidation]).
     * Returns the card's ticket (null when nothing is pending): releasing it before the render got a slot cancels it.
     */
    fun requestPreview(note: Note, darkTheme: Boolean): PreviewTicket? {
        previewInvalidation.observe(note)
        if (note.isLocked) return null
        val key = previewKey(note, darkTheme)
        if (previews.contains(key) || previewInvalidation.isFailed(key)) return null
        val request = previewRequests.want(key)
        val ticket = previewRequests.ticket(request)
        if (request.launched) return ticket
        request.launched = true
        val job = viewModelScope.launch {
            try {
                previewSlots.withPermit {
                    previewRequests.started(request)
                    val png = notes.renderPreview(note.id, previewWidthPx, darkTheme)
                    val image = png?.let {
                        withContext(Dispatchers.IO) { decodePreview(it)?.asImageBitmap() }
                    }
                    if (image != null) previews.put(key, image) else previewInvalidation.markFailed(key)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                previewInvalidation.markFailed(key)
            } catch (e: OutOfMemoryError) {
                previewInvalidation.markFailed(key) // a hostile note's preview must not crash the list
            } catch (e: StackOverflowError) {
                previewInvalidation.markFailed(key)
            } finally {
                previewRequests.finished(request)
            }
        }
        request.cancel = { job.cancel() }
        return ticket
    }

    fun selectFilter(newFilter: NoteFilter) {
        filter.value = newFilter
        clearSelection()
    }

    /** System BACK inside a folder: child -> parent folder, top-level folder -> All notes. */
    fun navigateUpFromFolder() {
        val state = uiState.value
        if (state.filter !is NoteFilter.InFolder) return
        selectFilter(parentFilterOf(state.folders, state.filter))
    }

    // --- view preferences ---
    fun setViewMode(mode: ViewMode) = viewModelScope.launch { preferences.setViewMode(mode) }
    fun setSortField(field: SortField) = viewModelScope.launch { preferences.setSortField(field) }
    fun toggleSortDirection() = viewModelScope.launch {
        val current = uiState.value.preferences.sortDirection
        preferences.setSortDirection(if (current == SortDirection.Descending) SortDirection.Ascending else SortDirection.Descending)
    }
    fun setPinFavorites(pin: Boolean) = viewModelScope.launch { preferences.setPinFavoritesToTop(pin) }
    fun setShowFolderCards(show: Boolean) = viewModelScope.launch { preferences.setShowFolderCards(show) }

    // --- selection ---
    fun startSelecting(first: Note? = null, firstFolder: Folder? = null) {
        selecting.value = true
        if (first != null) selection.value = setOf(first.id)
        if (firstFolder != null) folderSelection.value = setOf(firstFolder.id)
    }

    fun toggleSelected(note: Note) = selection.update { if (note.id in it) it - note.id else it + note.id }

    fun toggleSelected(folder: Folder) =
        folderSelection.update { if (folder.id in it) it - folder.id else it + folder.id }

    fun toggleSelectAll() {
        val state = uiState.value
        val allNotes = state.notes.mapTo(HashSet()) { it.id }
        val allFolders = (state.visibleFolders + state.trashFolders).mapTo(HashSet()) { it.id }
        val everything = selection.value.containsAll(allNotes) && folderSelection.value.containsAll(allFolders) &&
            selection.value.size == allNotes.size && folderSelection.value.size == allFolders.size
        selection.value = if (everything) emptySet() else allNotes
        folderSelection.value = if (everything) emptySet() else allFolders
    }

    fun clearSelection() {
        selecting.value = false
        selection.value = emptySet()
        folderSelection.value = emptySet()
    }

    // --- actions ---
    suspend fun createNote(): NoteId {
        val folderId = (filter.value as? NoteFilter.InFolder)?.folderId
        return notes.createNote(folderId)
    }

    fun createFolder(name: String, color: Long?) = viewModelScope.launch {
        val parent = (filter.value as? NoteFilter.InFolder)?.folderId
        runCatching { folders.createFolder(name, parent, color) }
    }

    /** Creates a folder under [parent] (null = top level), for the Move screen. Returns null when the name is refused. */
    suspend fun createFolderIn(name: String, color: Long?, parent: FolderId?): FolderId? =
        runCatching { folders.createFolder(name, parent, color) }.getOrNull()

    /** Moves the selected notes into [folderId] (null = top level, "Folders"), then leaves selection mode. */
    fun moveSelectionTo(folderId: FolderId?) = withSelection { notes.moveToFolder(it, folderId) }

    /**
     * "Import PDF": one note per job (see [planPdfImports]). Returns the created notes and the first failure, if any;
     * a failed file does not stop the others.
     */
    suspend fun importPdfs(jobs: List<PdfImportJob>): PdfImportOutcome {
        val folder = (filter.value as? NoteFilter.InFolder)?.folderId
        val created = ArrayList<NoteId>()
        var error: org.samesame.notes.core.data.pdf.PdfImportError? = null
        for (job in jobs) {
            try {
                created += notes.importPdf(job.files, job.title, folder)
            } catch (e: org.samesame.notes.core.data.pdf.PdfImportException) {
                if (error == null) error = e.reason
            } catch (e: java.io.IOException) {
                if (error == null) error = org.samesame.notes.core.data.pdf.PdfImportError.Corrupt
            }
        }
        return PdfImportOutcome(created, error)
    }

    fun renameFolder(id: org.samesame.notes.core.model.FolderId, name: String) = viewModelScope.launch {
        runCatching { folders.rename(id, name) }
    }

    /** Moves the selected folder subtrees and notes to the Trash in one go. */
    fun moveSelectionToTrash() = viewModelScope.launch {
        val folderIds = folderSelection.value.toList()
        val noteIds = selection.value.toList()
        if (folderIds.isNotEmpty()) folders.trash(folderIds)
        if (noteIds.isNotEmpty()) notes.moveToTrash(noteIds)
        clearSelection()
    }

    /** Restores the selected folders (with their subtrees) and notes from the Trash. */
    fun restoreSelection() = viewModelScope.launch {
        val folderIds = folderSelection.value.toList()
        val noteIds = selection.value.toList()
        if (folderIds.isNotEmpty()) folders.restore(folderIds)
        if (noteIds.isNotEmpty()) notes.restoreFromTrash(noteIds)
        clearSelection()
    }

    /** Permanently removes the selected folders (with their subtrees) and notes. */
    fun deleteSelectionForever() = viewModelScope.launch {
        val folderIds = folderSelection.value.toList()
        val noteIds = selection.value.toList()
        if (folderIds.isNotEmpty()) folders.deletePermanently(folderIds)
        if (noteIds.isNotEmpty()) notes.deleteForever(noteIds)
        noteIds.forEach(previewInvalidation::forget)
        clearSelection()
    }

    fun emptyTrash() = viewModelScope.launch {
        // Notes first: the folder path then finds no note rows left and only removes the folder tree.
        notes.deleteForever(notes.observeNotes(NoteFilter.Trash, SortOrder()).first().map { it.id })
        val trashedFolders = folders.observeTrashedFolders().first().map { it.id }
        if (trashedFolders.isNotEmpty()) folders.deletePermanently(trashedFolders)
        clearSelection()
    }

    /** Trashes folders picked in Manage folders and leaves selection mode. */
    fun trashFolders(ids: List<FolderId>) = viewModelScope.launch {
        if (ids.isNotEmpty()) folders.trash(ids)
        clearSelection()
    }

    fun duplicateSelection() = withSelection { ids -> ids.forEach { notes.duplicateNote(it) } }

    /** Adds the selection to favourites, or removes them when every selected note already is one. */
    fun toggleSelectionFavorite() = withSelection { ids ->
        val selected = uiState.value.notes.filter { it.id in ids }
        notes.setFavorite(ids, !selected.all { it.isFavorite })
    }

    /** Locks the selected notes with the verified [token] (closed afterwards). */
    fun lockSelection(token: org.samesame.notes.core.security.LockToken) = viewModelScope.launch {
        try {
            val ids = selection.value.toList()
            if (ids.isNotEmpty()) runCatching { notes.lockNotes(ids, token) }
        } finally {
            token.close()
            clearSelection()
        }
    }

    /** Removes the lock from the selected locked notes. */
    fun unlockSelection(token: org.samesame.notes.core.security.LockToken) = viewModelScope.launch {
        try {
            val locked = uiState.value.notes.filter { it.id in selection.value && it.isLocked }.map { it.id }
            if (locked.isNotEmpty()) runCatching { notes.unlockNotes(locked, token) }
        } finally {
            token.close()
            clearSelection()
        }
    }

    /** Decrypts a locked note into its working copy, then [onOpened] navigates to the editor. */
    fun openLocked(id: NoteId, token: org.samesame.notes.core.security.LockToken, onOpened: () -> Unit) = viewModelScope.launch {
        val ok = try {
            runCatching { notes.openLockedNote(id, token) }.isSuccess
        } finally {
            token.close()
        }
        if (ok) onOpened()
    }

    private fun withSelection(block: suspend (List<NoteId>) -> Unit) = viewModelScope.launch {
        val ids = selection.value.toList()
        if (ids.isNotEmpty()) block(ids)
        clearSelection()
    }
}



private const val PREVIEW_WIDTH_PX = 384

/**
 * Width of the first-page previews: [PREVIEW_WIDTH_PX], but never more than half the screen width (the widest card, the
 * large grid's two columns, is a little under half), so small low-end screens render and keep smaller bitmaps. Cards
 * crop the preview to their own size, so they look the same.
 */
internal fun previewWidthFor(screenWidthPx: Int): Int =
    if (screenWidthPx <= 0) PREVIEW_WIDTH_PX else minOf(PREVIEW_WIDTH_PX, (screenWidthPx + 1) / 2)
private const val PREVIEW_CACHE_ENTRIES = 24

/**
 * Decodes a card preview straight into GPU memory (a hardware bitmap): the first frame that shows the card has no
 * texture upload to do on the render thread, and the cached previews don't sit on the Java heap. Falls back to a
 * normal bitmap where a hardware one can't be made.
 */
private fun decodePreview(png: ByteArray): android.graphics.Bitmap? {
    val hardware = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.HARDWARE }
    return runCatching { BitmapFactory.decodeByteArray(png, 0, png.size, hardware) }.getOrNull()
        ?: BitmapFactory.decodeByteArray(png, 0, png.size)
}

/** A PDF copied to the cache, with the title its note gets (the file name without ".pdf"). */
data class PdfSource(val file: java.io.File, val title: String)

/** One note to create: all [files] become its pages, in order. */
data class PdfImportJob(val files: List<java.io.File>, val title: String)

data class PdfImportOutcome(val created: List<NoteId>, val error: org.samesame.notes.core.data.pdf.PdfImportError?)

/**
 * The filter one level up from [filter]: a nested folder's parent, a top-level folder's All notes.
 * Any other filter (and an unknown folder) is unchanged; an unknown folder maps to All notes.
 */
internal fun parentFilterOf(folders: List<Folder>, filter: NoteFilter): NoteFilter {
    if (filter !is NoteFilter.InFolder) return filter
    val parentId = folders.firstOrNull { it.id == filter.folderId }?.parentId
    return if (parentId != null) NoteFilter.InFolder(parentId) else NoteFilter.All
}

/** "Add them all to one note" (one job) or "Create separate notes" (one job per PDF). */
internal fun planPdfImports(sources: List<PdfSource>, oneNote: Boolean): List<PdfImportJob> = when {
    sources.isEmpty() -> emptyList()
    oneNote -> listOf(PdfImportJob(sources.map { it.file }, sources.first().title))
    else -> sources.map { PdfImportJob(listOf(it.file), it.title) }
}

/** File name shown to the user minus a trailing ".pdf" (any case); blank names stay blank. */
internal fun pdfTitle(displayName: String?): String = displayName.orEmpty().trim().replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "").trim()

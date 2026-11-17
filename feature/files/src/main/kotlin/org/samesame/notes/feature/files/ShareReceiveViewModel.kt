package org.samesame.notes.feature.files

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.samesame.notes.core.data.repository.FilesRepository
import org.samesame.notes.core.data.transfer.ImportSource
import org.samesame.notes.core.data.transfer.NoteImportService
import org.samesame.notes.core.model.files.DuplicatePolicy
import org.samesame.notes.core.model.files.FileFolder
import org.samesame.notes.core.model.files.FileFolderId
import org.samesame.notes.core.model.files.ImportResult
import org.samesame.notes.core.model.files.LibraryFile
import javax.inject.Inject

/** One shared or opened file. [name] is editable in the sheet. */
data class ReceivedItem(val uri: Uri, val name: String, val mimeType: String?, val kind: ReceiveKind)

sealed interface ReceivePhase {
    data object Choosing : ReceivePhase
    data class Saving(val progress: ImportProgress?) : ReceivePhase
    /** Files were saved. [opened] is the file the Open button hands to the system (the single one saved). */
    data class Done(val saved: Int, val failures: List<ImportFailure>, val opened: LibraryFile?) : ReceivePhase
}

data class ReceiveUiState(
    val items: List<ReceivedItem> = emptyList(),
    val toNewNote: Boolean = false,
    val folderId: FileFolderId? = null,
    val phase: ReceivePhase = ReceivePhase.Choosing,
    val duplicates: List<ImportResult.Duplicate> = emptyList(),
    val folders: List<FileFolder> = emptyList(),
) {
    val canNewNote: Boolean get() = items.isNotEmpty() && items.all { ReceiveRouting.canBecomeNote(it.kind) }
    /** Some item can't go into a note, so the sheet says it will be saved to Files. */
    val hasFilesOnly: Boolean get() = items.any { it.kind == ReceiveKind.Other }
}

sealed interface ReceiveEvent {
    data class OpenNote(val noteId: String) : ReceiveEvent
    data object NoteFailed : ReceiveEvent
    /** Text files over the size cap were kept in Files instead of becoming notes. */
    data object TextTooLarge : ReceiveEvent
}

@HiltViewModel
class ShareReceiveViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: FilesRepository,
    private val noteImporter: NoteImportService,
    private val notes: org.samesame.notes.core.data.repository.NoteRepository,
    private val prefs: FilesPreferences,
) : ViewModel() {

    private val state = MutableStateFlow(ReceiveUiState())
    private val folders = repo.observeFolders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val uiState: StateFlow<ReceiveUiState> = kotlinx.coroutines.flow.combine(state, folders) { s, f -> s.copy(folders = f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiveUiState())

    private val eventChannel = Channel<ReceiveEvent>(Channel.BUFFERED)
    val events: Flow<ReceiveEvent> = eventChannel.consumeAsFlow()
    private var sourceApp: String? = null
    private var job: Job? = null
    private var started = false

    /** Called once by the activity with the intent's files (a recreated activity keeps this ViewModel's state). */
    fun start(items: List<ReceivedItem>, sourceApp: String?) {
        if (started) return
        started = true
        this.sourceApp = sourceApp
        val last = prefs.lastFolderId?.takeIf { it.isNotEmpty() }?.let(::FileFolderId)
        state.value = ReceiveUiState(
            items = items.take(FilesImporter.MAX_ITEMS),
            toNewNote = ReceiveRouting.defaultToNewNote(items.map { it.kind }),
            folderId = last,
        )
        // The remembered folder may be gone by now.
        viewModelScope.launch {
            val known = folders.value
            if (last != null && known.isNotEmpty() && known.none { it.id == last }) state.update { it.copy(folderId = null) }
        }
    }

    /** Shared plain text: one new note (title = the subject), then the editor opens. */
    fun startText(shared: SharedText) {
        if (started) return
        started = true
        job = viewModelScope.launch {
            val id = runCatching { notes.importText(shared.body, shared.title).value }.getOrNull()
            if (id != null) eventChannel.send(ReceiveEvent.OpenNote(id)) else eventChannel.send(ReceiveEvent.NoteFailed)
        }
    }

    fun rename(index: Int, name: String) = state.update { s -> s.copy(items = s.items.mapIndexed { i, it -> if (i == index) it.copy(name = name) else it }) }
    fun setNewNote(value: Boolean) = state.update { it.copy(toNewNote = value && it.canNewNote) }
    fun setFolder(id: FileFolderId?) = state.update { it.copy(folderId = id) }

    fun cancel() { job?.cancel(); job = null; state.update { it.copy(phase = ReceivePhase.Choosing) } }

    fun save() {
        val s = state.value
        if (s.items.isEmpty() || job?.isActive == true) return
        if (s.toNewNote && s.canNewNote) saveAsNotes(s) else saveToFiles(s)
    }

    private fun saveToFiles(s: ReceiveUiState) {
        prefs.lastFolderId = s.folderId?.value ?: ""
        state.update { it.copy(phase = ReceivePhase.Saving(null)) }
        job = viewModelScope.launch {
            val report = FilesImporter.run(
                items = s.items,
                nameOf = { it.name },
                import = { item, policy, onProgress ->
                    repo.importFromUri(item.uri, s.folderId, displayName = item.name.takeIf { it.isNotBlank() }, sourceApp = sourceApp, onDuplicate = policy, onProgress = onProgress)
                },
                onProgress = { p -> state.update { it.copy(phase = ReceivePhase.Saving(p)) } },
            )
            val saved = report.imported.size
            state.update {
                it.copy(
                    duplicates = report.duplicates,
                    phase = if (report.duplicates.isNotEmpty()) ReceivePhase.Saving(null)
                    else ReceivePhase.Done(saved, report.failures, report.imported.singleOrNull()),
                )
            }
            if (report.duplicates.isNotEmpty()) pendingDone = PendingDone(saved, report.failures, report.imported.singleOrNull())
        }
    }

    private class PendingDone(var saved: Int, val failures: List<ImportFailure>, var opened: LibraryFile?)
    private var pendingDone: PendingDone? = null

    fun resolveDuplicate(keepBoth: Boolean) {
        val first = state.value.duplicates.firstOrNull() ?: return
        viewModelScope.launch {
            val added = repo.resolveDuplicate(first.pending, keepBoth)
            val rest = state.value.duplicates.drop(1)
            val pd = pendingDone
            if (pd != null && added != null) { pd.saved++; pd.opened = if (pd.saved == 1) added else null }
            state.update {
                it.copy(
                    duplicates = rest,
                    phase = if (rest.isEmpty() && pd != null) ReceivePhase.Done(pd.saved, pd.failures, pd.opened) else it.phase,
                )
            }
        }
    }

    private fun saveAsNotes(s: ReceiveUiState) {
        state.update { it.copy(phase = ReceivePhase.Saving(null)) }
        job = viewModelScope.launch {
            var firstId: String? = null
            var failed = 0
            val tooLarge = ArrayList<ReceivedItem>()
            val handled = HashSet<ReceivedItem>()
            s.items.forEach { item ->
                if (item.kind == ReceiveKind.Image || item.kind == ReceiveKind.Audio) {
                    if (item in handled) return@forEach
                    val id = if (item.kind == ReceiveKind.Image) importPictures(s, handled) else importAudioItem(s, item)
                    if (id == null) failed++ else if (firstId == null) firstId = id
                    return@forEach
                }
                val source = ImportSource(item.name, item.mimeType, item.uri.lastPathSegment) {
                    context.contentResolver.openInputStream(item.uri) ?: throw java.io.FileNotFoundException(item.uri.toString())
                }
                when (val r = noteImporter.import(source)) {
                    is org.samesame.notes.core.data.transfer.ImportResult.Imported -> if (firstId == null) firstId = r.id.value
                    is org.samesame.notes.core.data.transfer.ImportResult.Failed ->
                        if (r.reason == org.samesame.notes.core.data.transfer.ImportFailure.TOO_LARGE) tooLarge += item else failed++
                }
            }
            if (tooLarge.isNotEmpty()) {
                eventChannel.send(ReceiveEvent.TextTooLarge)
                // Kept in Files instead: the large file is still received.
                if (firstId == null) { job = null; saveToFiles(s.copy(items = tooLarge)); return@launch }
                tooLarge.forEach { item ->
                    runCatching { repo.importFromUri(item.uri, s.folderId, displayName = item.name.takeIf { it.isNotBlank() }, sourceApp = sourceApp, onDuplicate = org.samesame.notes.core.model.files.DuplicatePolicy.KeepBoth, onProgress = { _, _ -> }) }
                }
            }
            val id = firstId
            if (id != null) eventChannel.send(ReceiveEvent.OpenNote(id))
            else { eventChannel.send(ReceiveEvent.NoteFailed); state.update { it.copy(phase = ReceivePhase.Choosing) } }
        }
    }

    /** Keeps [item] in Files too (a receive always does for pictures and audio); the entry id, null when it could not be saved. */
    private suspend fun keepInFiles(s: ReceiveUiState, item: ReceivedItem): LibraryFile? =
        runCatching {
            repo.importFromUri(item.uri, s.folderId, displayName = item.name.takeIf { it.isNotBlank() }, sourceApp = sourceApp, onDuplicate = DuplicatePolicy.KeepBoth, onProgress = { _, _ -> })
        }.getOrNull().let { (it as? ImportResult.Imported)?.file }

    /** All received pictures of this share become ONE note (stacked down the page). Returns its id, null on failure. */
    private suspend fun importPictures(s: ReceiveUiState, handled: MutableSet<ReceivedItem>): String? {
        val pictures = s.items.filter { it.kind == ReceiveKind.Image }
        handled += pictures
        val prepared = pictures.mapNotNull { item ->
            val file = keepInFiles(s, item)
            ReceivedMedia.image(context, item.uri, item.name, item.mimeType, file?.id?.value)
        }
        if (prepared.isEmpty()) return null
        val title = if (pictures.size == 1) pictures[0].name.substringBeforeLast('.', pictures[0].name).trim() else ""
        return runCatching { notes.importImages(prepared, title).value }.getOrNull()
    }

    private suspend fun importAudioItem(s: ReceiveUiState, item: ReceivedItem): String? {
        val ext = ReceiveRouting.audioExtension(item.name, item.mimeType)
        val file = ReceivedMedia.audioToScratch(context, item.uri, ext) ?: return null
        try {
            keepInFiles(s, item)
            val title = item.name.substringBeforeLast('.', item.name).trim()
            return runCatching { notes.importAudio(file, ext, title, ReceivedMedia.audioDurationMs(file)).value }.getOrNull()
        } finally {
            file.delete()
        }
    }

    fun contentUri(file: LibraryFile): Uri = repo.contentUri(file)
}

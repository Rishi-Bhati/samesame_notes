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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.repository.FilesRepository
import org.samesame.notes.core.data.transfer.FileConversionService
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.io.LimitedInputStream
import org.samesame.notes.core.model.io.readBytesLimited
import org.samesame.notes.core.model.saveas.SaveAsOptions
import org.samesame.notes.core.model.saveas.SaveOption
import org.samesame.notes.core.model.textimport.TextImport
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
    /** [elsewhere]: a copy or a PDF is being written to a picked location (not Files). */
    data class Saving(val progress: ImportProgress?, val elsewhere: Boolean = false) : ReceivePhase
    /** Files were saved. [opened] is the file the Open button hands to the system (the single one saved). */
    data class Done(val saved: Int, val failures: List<ImportFailure>, val opened: LibraryFile?) : ReceivePhase
}

data class ReceiveUiState(
    val items: List<ReceivedItem> = emptyList(),
    /** Where Save puts the items: one of [options]. */
    val destination: SaveOption = SaveOption.SaveToFiles,
    val folderId: FileFolderId? = null,
    val phase: ReceivePhase = ReceivePhase.Choosing,
    val duplicates: List<ImportResult.Duplicate> = emptyList(),
    val folders: List<FileFolder> = emptyList(),
) {
    val toNewNote: Boolean get() = destination == SaveOption.SaveAsNote
    val canNewNote: Boolean get() = items.isNotEmpty() && items.all { ReceiveRouting.canBecomeNote(it.kind) }
    /** Some item can't go into a note, so the sheet says it will be saved to Files. */
    val hasFilesOnly: Boolean get() = items.any { it.kind == ReceiveKind.Other }
    /** The destinations the sheet offers for these items (Files, New note, Save a copy, PDF), in order. */
    val options: List<SaveOption> get() = ReceiveRouting.options(items)
}

sealed interface ReceiveEvent {
    data class OpenNote(val noteId: String) : ReceiveEvent
    data object NoteFailed : ReceiveEvent
    /** Text files over the size cap were kept in Files instead of becoming notes. */
    data object TextTooLarge : ReceiveEvent
    /** The copy or the PDF was written to the picked location. */
    data object SavedElsewhere : ReceiveEvent
    /** The copy or the PDF could not be written ([tooLarge]: over a size cap). */
    data class SaveElsewhereFailed(val tooLarge: Boolean) : ReceiveEvent
}

@HiltViewModel
class ShareReceiveViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: FilesRepository,
    private val noteImporter: NoteImportService,
    private val notes: org.samesame.notes.core.data.repository.NoteRepository,
    private val prefs: FilesPreferences,
    private val conversion: FileConversionService,
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
            destination = if (ReceiveRouting.defaultToNewNote(items.map { it.kind })) SaveOption.SaveAsNote else SaveOption.SaveToFiles,
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
    fun setNewNote(value: Boolean) = setDestination(if (value) SaveOption.SaveAsNote else SaveOption.SaveToFiles)
    fun setDestination(option: SaveOption) = state.update { if (option in it.options) it.copy(destination = option) else it }
    fun setFolder(id: FileFolderId?) = state.update { it.copy(folderId = id) }

    fun cancel() { job?.cancel(); job = null; state.update { it.copy(phase = ReceivePhase.Choosing) } }

    /** Save for Files and New note; Save a copy and PDF go through the file picker first (see [saveElsewhere]). */
    fun save() {
        val s = state.value
        if (s.items.isEmpty() || job?.isActive == true) return
        if (s.toNewNote && s.canNewNote) saveAsNotes(s) else saveToFiles(s)
    }

    /** The file name and MIME type the file picker suggests for the current destination (Save a copy or PDF). */
    fun pickerTarget(): Pair<String, String>? {
        val s = state.value
        val first = s.items.firstOrNull() ?: return null
        return when (s.destination) {
            SaveOption.SaveCopy -> SaveAsOptions.suggestedName(first.name) to (first.mimeType?.substringBefore(';')?.trim()?.takeIf { it.contains('/') && it != "*/*" } ?: "application/octet-stream")
            SaveOption.ExportPdf -> SaveAsOptions.suggestedName(if (s.items.size == 1) first.name else null, "pdf") to "application/pdf"
            else -> null
        }
    }

    /** Writes the single item ([SaveOption.SaveCopy]) or the PDF of the items ([SaveOption.ExportPdf]) to [target]. */
    fun saveElsewhere(target: Uri) {
        val s = state.value
        if (s.items.isEmpty() || job?.isActive == true) return
        val destination = s.destination
        if (destination != SaveOption.SaveCopy && destination != SaveOption.ExportPdf) return
        state.update { it.copy(phase = ReceivePhase.Saving(null, elsewhere = true)) }
        job = viewModelScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val out = resolver.openOutputStream(target, "w") ?: throw java.io.FileNotFoundException("Can't write the picked file")
                    out.use { if (destination == SaveOption.SaveCopy) copyItem(s.items.single(), it) else writePdf(s.items, it) }
                }
            }
            val error = ok.exceptionOrNull()
            if (error is kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { deleteQuietly(target) }
                throw error
            }
            if (error == null) {
                eventChannel.send(ReceiveEvent.SavedElsewhere)
            } else {
                withContext(Dispatchers.IO) { deleteQuietly(target) }
                val tooLarge = error is org.samesame.notes.core.model.io.InputTooLargeException
                state.update { it.copy(phase = ReceivePhase.Choosing) }
                eventChannel.send(ReceiveEvent.SaveElsewhereFailed(tooLarge))
            }
        }
    }

    private fun copyItem(item: ReceivedItem, out: java.io.OutputStream) {
        val input = context.contentResolver.openInputStream(item.uri) ?: throw java.io.FileNotFoundException("Can't read the shared file")
        LimitedInputStream(input, Limits.MAX_LIBRARY_FILE_BYTES, "Shared file").use { it.copyTo(out) }
    }

    private suspend fun writePdf(items: List<ReceivedItem>, out: java.io.OutputStream) {
        val text = items.singleOrNull()?.takeIf { it.kind == ReceiveKind.Text }
        if (text != null) {
            val body = TextImport.decode(readText(text))
            val markdown = SaveAsOptions.isMarkdown(text.name, text.mimeType)
            conversion.exportText(body, SaveAsOptions.noteTitle(text.name), markdown, org.samesame.notes.core.export.ExportFormat.PDF, out)
            return
        }
        val pictures = items.filter { it.kind == ReceiveKind.Image }
        conversion.imagesToPdf(pictures.size, { i -> pictures[i].let { ReceivedMedia.image(context, it.uri, it.name, it.mimeType, null)?.bytes } }, out)
    }

    /** At most the text cap of [item] (one byte more fails with InputTooLargeException). */
    private fun readText(item: ReceivedItem): ByteArray =
        (context.contentResolver.openInputStream(item.uri) ?: throw java.io.FileNotFoundException("Can't read the shared file")).use {
            it.readBytesLimited(Limits.MAX_TEXT_IMPORT_BYTES, "Text file")
        }

    private fun deleteQuietly(target: Uri) {
        runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, target) }
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
                if (item.kind == ReceiveKind.Text && SaveAsOptions.isMarkdown(item.name, item.mimeType)) {
                    when (val id = importMarkdownItem(item)) {
                        null -> tooLarge += item
                        "" -> failed++
                        else -> if (firstId == null) firstId = id
                    }
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

    /**
     * A Markdown file as a Markdown note, its source kept as written (line breaks become LF, like the editor).
     * Returns the note id, null when over the text cap, "" when it could not be read or saved.
     */
    private suspend fun importMarkdownItem(item: ReceivedItem): String? {
        val bytes = try {
            withContext(Dispatchers.IO) { readText(item) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: org.samesame.notes.core.model.io.InputTooLargeException) {
            return null
        } catch (_: Exception) {
            return ""
        }
        return runCatching { notes.importMarkdown(TextImport.decode(bytes), SaveAsOptions.noteTitle(item.name)).value }.getOrDefault("")
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

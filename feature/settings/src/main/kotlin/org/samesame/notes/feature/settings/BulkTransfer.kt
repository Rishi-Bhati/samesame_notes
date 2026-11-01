package org.samesame.notes.feature.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.data.transfer.BulkExportResult
import org.samesame.notes.core.data.transfer.ImportFailure
import org.samesame.notes.core.data.transfer.ImportSource
import org.samesame.notes.core.data.transfer.ImportSummary
import org.samesame.notes.core.data.transfer.NoteImportService
import org.samesame.notes.core.data.transfer.NoteTransferService
import org.samesame.notes.core.data.transfer.TransferItem
import org.samesame.notes.core.export.ExportFormat
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteFilter
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.SortOrder
import java.io.IOException
import javax.inject.Inject

/** Which notes are ticked on the Export notes page. Locked notes can't be ticked (they are never exported). */
data class ExportSelection(val selected: Set<NoteId> = emptySet()) {
    fun toggle(id: NoteId): ExportSelection = copy(selected = if (id in selected) selected - id else selected + id)

    /** "All": ticks every selectable note, or clears the ticks when they are all ticked already. */
    fun toggleAll(selectable: List<NoteId>): ExportSelection =
        if (allSelected(selectable)) ExportSelection() else ExportSelection(selectable.toSet())

    fun allSelected(selectable: List<NoteId>): Boolean = selectable.isNotEmpty() && selected.containsAll(selectable)

    /** Drops ticks of notes that disappeared or became locked meanwhile. */
    fun retain(selectable: List<NoteId>): ExportSelection = copy(selected = selected.intersect(selectable.toSet()))

    /** Ticked ids in list order. */
    fun ordered(selectable: List<NoteId>): List<NoteId> = selectable.filter { it in selected }
}

/** One line of a result summary: what happened ([kind]) to how many files/notes. */
data class SummaryLine(val kind: Kind, val count: Int) {
    enum class Kind { Imported, Exported, SkippedLocked, Unsupported, Legacy, Protected, Failed }
}

/** Result summary lines, the success line first (always shown, even at 0), then every non-zero problem. */
object BulkSummary {
    fun of(summary: ImportSummary): List<SummaryLine> = buildList {
        add(SummaryLine(SummaryLine.Kind.Imported, summary.imported.size))
        val f = summary.failures
        f[ImportFailure.UNSUPPORTED]?.let { add(SummaryLine(SummaryLine.Kind.Unsupported, it)) }
        f[ImportFailure.LEGACY]?.let { add(SummaryLine(SummaryLine.Kind.Legacy, it)) }
        f[ImportFailure.PROTECTED]?.let { add(SummaryLine(SummaryLine.Kind.Protected, it)) }
        val other = (f[ImportFailure.INVALID] ?: 0) + (f[ImportFailure.IO] ?: 0)
        if (other > 0) add(SummaryLine(SummaryLine.Kind.Failed, other))
    }.filter { it.kind == SummaryLine.Kind.Imported || it.count > 0 }

    fun of(result: BulkExportResult): List<SummaryLine> = buildList {
        add(SummaryLine(SummaryLine.Kind.Exported, result.exported))
        if (result.skippedLocked > 0) add(SummaryLine(SummaryLine.Kind.SkippedLocked, result.skippedLocked))
        if (result.failed > 0) add(SummaryLine(SummaryLine.Kind.Failed, result.failed))
    }

    /**
     * The MIME type to create a SAF document with. Providers append the MIME type's extension when the name's
     * extension doesn't map to that type (`Plan.ssnote` as application/zip would become `Plan.ssnote.zip`), so a
     * type is only passed when [extensionMime] (the platform's type for the name's extension) agrees with it.
     */
    fun documentMime(mime: String, extensionMime: String?): String =
        if (extensionMime != null && extensionMime.equals(mime, ignoreCase = true)) mime else "application/octet-stream"
}

/** State of the bulk Import / Export run shown over the Settings pages. */
sealed interface BulkState {
    data object Idle : BulkState
    data class Running(val importing: Boolean, val done: Int, val total: Int, val name: String) : BulkState
    data class Finished(val importing: Boolean, val lines: List<SummaryLine>) : BulkState
}

/**
 * Settings > Import notes / Export notes: bulk import of picked files (`.sdocx`, `.ssnote`, PDF; each becomes a note
 * in the default folder) and bulk export of ticked notes in any "Save as file" format into a folder picked through
 * the Storage Access Framework. Runs in the ViewModel so rotation doesn't interrupt it.
 */
@HiltViewModel
class BulkTransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: NoteImportService,
    private val transfer: NoteTransferService,
    notes: NoteRepository,
) : ViewModel() {

    /** Every note outside the Recycle bin, newest first (as the default list). */
    val notes: StateFlow<List<Note>> = notes.observeNotes(NoteFilter.All, SortOrder())
        .map { list -> list.filter { !it.isInTrash } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selection = MutableStateFlow(ExportSelection())
    val selection: StateFlow<ExportSelection> = _selection.asStateFlow()

    private val _state = MutableStateFlow<BulkState>(BulkState.Idle)
    val state: StateFlow<BulkState> = _state.asStateFlow()

    fun toggle(id: NoteId) { _selection.value = _selection.value.toggle(id) }
    fun toggleAll(selectable: List<NoteId>) { _selection.value = _selection.value.toggleAll(selectable) }
    fun retain(selectable: List<NoteId>) { _selection.value = _selection.value.retain(selectable) }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value is BulkState.Running) return
        _state.value = BulkState.Running(importing = true, done = 0, total = uris.size, name = "")
        viewModelScope.launch {
            val sources = withContext(Dispatchers.IO) { uris.map { it.toSource() } }
            val summary = importer.importAll(sources) { done, total, name ->
                _state.value = BulkState.Running(importing = true, done = done, total = total, name = name)
            }
            _state.value = BulkState.Finished(importing = true, lines = BulkSummary.of(summary))
        }
    }

    fun export(ids: List<NoteId>, format: ExportFormat, tree: Uri) {
        if (ids.isEmpty() || _state.value is BulkState.Running) return
        _state.value = BulkState.Running(importing = false, done = 0, total = ids.size, name = "")
        viewModelScope.launch {
            val result = try {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                transfer.exportEach(
                    ids, format,
                    onProgress = { done, total, title -> _state.value = BulkState.Running(importing = false, done = done, total = total, name = title) },
                ) { item -> writeInto(parent, item) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                BulkExportResult(exported = 0, skippedLocked = 0, failed = ids.size)
            }
            if (result.exported > 0) _selection.value = ExportSelection()
            _state.value = BulkState.Finished(importing = false, lines = BulkSummary.of(result))
        }
    }

    fun dismissResult() {
        if (_state.value is BulkState.Finished) _state.value = BulkState.Idle
    }

    private fun writeInto(parent: Uri, item: TransferItem) {
        val resolver = context.contentResolver
        val ext = item.name.substringAfterLast('.', "").lowercase()
        val mime = BulkSummary.documentMime(item.mimeType, MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext))
        val doc = DocumentsContract.createDocument(resolver, parent, mime, item.name) ?: throw IOException("createDocument failed")
        try {
            (resolver.openOutputStream(doc) ?: throw IOException("no output stream")).use { out ->
                item.file.inputStream().use { it.copyTo(out) }
            }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
    }

    private fun Uri.toSource(): ImportSource {
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst() && !c.isNull(i)) c.getString(i) else null
            }
        }.getOrNull()
        val mime = runCatching { resolver.getType(this) }.getOrNull()
        val uri = this
        return ImportSource(name, mime, lastPathSegment) {
            resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        }
    }
}

package org.samesame.notes

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.data.transfer.ImportFailure
import org.samesame.notes.core.data.transfer.ImportSource
import org.samesame.notes.core.data.transfer.NoteImportService
import org.samesame.notes.core.model.NoteId
import javax.inject.Inject

/**
 * Files opened from another app (VIEW / SEND / SEND_MULTIPLE: `.sdocx`, `.ssnote`, PDF) are IMPORTED into the catalog
 * first, as ordinary notes in the default folder, and the note is then opened from the catalog, so it survives the
 * source file being deleted. Kept in a ViewModel so a configuration change cannot import a file twice; the same URI
 * opened again while the app runs reopens the note it already produced (unless that note was deleted or trashed).
 */
@HiltViewModel
class FileImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: NoteImportService,
    private val notes: NoteRepository,
) : ViewModel() {

    data class State(
        val importing: Boolean = false,
        /** Note to open (one file imported, or one of several). */
        val noteId: String? = null,
        /** Why the (single) file was not imported. */
        val error: ImportFailure? = null,
        /** Several files: (imported, failed), for the summary toast. */
        val summary: Pair<Int, Int>? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private val recent = ImportDedup<String, NoteId>()

    fun import(files: List<IncomingFile>) {
        if (files.isEmpty()) return
        job?.cancel()
        _state.value = State(importing = true)
        job = viewModelScope.launch {
            if (files.size == 1) importOne(files.single()) else importMany(files)
        }
    }

    private suspend fun importOne(file: IncomingFile) {
        val key = file.uri.toString()
        recent.get(key)?.let { id ->
            val note = notes.observeNote(id).first()
            if (note != null && !note.isInTrash) {
                log { "Already imported $key as ${id.value}; opening it" }
                _state.value = State(noteId = id.value)
                return
            }
            recent.forget(key)
        }
        when (val r = importer.import(file.source())) {
            is org.samesame.notes.core.data.transfer.ImportResult.Imported -> {
                log { "Imported $key as ${r.id.value}" }
                recent.put(key, r.id)
                _state.value = State(noteId = r.id.value)
            }
            is org.samesame.notes.core.data.transfer.ImportResult.Failed -> {
                log { "Import of $key failed: ${r.reason}" }
                _state.value = State(error = r.reason)
            }
        }
    }

    private suspend fun importMany(files: List<IncomingFile>) {
        val summary = importer.importAll(files.map { it.source() })
        log { "Imported ${summary.imported.size} of ${files.size} shared files (${summary.failures})" }
        _state.value = State(
            noteId = summary.imported.singleOrNull()?.value,
            summary = summary.imported.size to summary.failed,
        )
    }

    private fun IncomingFile.source() = ImportSource(displayName, mimeType, uri.lastPathSegment) {
        context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())
    }

    /** Clears the one-shot result after navigation / error reporting. */
    fun consume() {
        _state.value = _state.value.copy(noteId = null, error = null, summary = null)
    }

    /** Debug builds only, like VoiceLog: source URIs carry file names, which are personal data (security review L5). */
    private val debugLogging = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private inline fun log(message: () -> String) {
        if (debugLogging) Log.i(TAG, message())
    }

    private companion object {
        const val TAG = "FileImport"
    }
}

/** Remembers the last few imports (source key -> note) so reopening the same file does not create another copy. */
class ImportDedup<K, V>(private val capacity: Int = 16) {
    private val map = object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > capacity
    }

    @Synchronized fun get(key: K): V? = map[key]
    @Synchronized fun put(key: K, value: V) { map[key] = value }
    @Synchronized fun forget(key: K) { map.remove(key) }
}


package org.samesame.notes.feature.textdoc

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.textdoc.LineEnding
import org.samesame.notes.core.model.textdoc.TextDocument
import org.samesame.notes.core.model.textdoc.TextDocumentCodec
import org.samesame.notes.core.model.textdoc.TextEncoding
import javax.inject.Inject

/** Everything the text document screen draws. */
data class TextDocUiState(
    val text: String = "",
    val encoding: TextEncoding = TextEncoding.Utf8,
    val lineEnding: LineEnding = LineEnding.Lf,
    val hasBom: Boolean = false,
    val isModified: Boolean = false,
    val isReadOnly: Boolean = false,
    val isMonospace: Boolean = false,
    val isWrap: Boolean = true,
    val isHtmlPreview: Boolean = false,
    val isMarkdownPreview: Boolean = false,
    val isMarkdown: Boolean = false,
    val isLockedNote: Boolean = false,
    val findQuery: String = "",
    val replaceQuery: String = "",
    val activeMatchIndex: Int = -1,
    val matchCount: Int = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val loadFailed: Boolean = false,
    /** The file's name, when the caller knows it. */
    val title: String? = null,
)

/**
 * The text editor. Text is held as one string with `\n` endings; encoding, line ending and byte-order mark are kept so
 * saving writes the file back in its original form. Files entries save through the repository; other documents through
 * their content URI.
 */
@HiltViewModel
class TextDocViewModel @Inject constructor(
    private val files: TextDocFileStore,
    private val uris: TextDocUriStore,
    private val notes: TextDocNoteStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TextDocUiState())
    val uiState: StateFlow<TextDocUiState> = _uiState.asStateFlow()

    private val undoRedo = UndoRedoState(Limits.MAX_UNDO_STEPS)
    private var documentUri: Uri? = null
    private var libraryFileId: FileId? = null
    private var currentNoteId: String? = null
    private var autosaveJob: Job? = null
    private var writable = true
    private var savedText = ""
    private var matches: List<IntRange> = emptyList()

    /** Loads [uri], or the Files entry [libraryFileId], or the note [noteId], or an empty untitled document when all are null. */
    fun load(
        uri: Uri? = null,
        libraryFileId: String? = null,
        writable: Boolean = true,
        displayName: String? = null,
        noteId: String? = null,
        mimeType: String? = null,
    ) {
        autosaveJob?.cancel()
        viewModelScope.launch {
            currentNoteId = noteId
            this@TextDocViewModel.writable = writable
            _uiState.update { it.copy(isLoading = true, loadFailed = false) }

            if (noteId != null) {
                this@TextDocViewModel.libraryFileId = null
                documentUri = null
                try {
                    val note = notes.getNote(noteId)
                    if (note == null) {
                        _uiState.update { it.copy(isLoading = false, loadFailed = true) }
                        return@launch
                    }
                    val isLocked = note.isLocked
                    val text = if (isLocked) "" else (notes.readMarkdown(noteId) ?: "")
                    savedText = text
                    undoRedo.reset(text)
                    matches = emptyList()
                    _uiState.value = TextDocUiState(
                        text = text,
                        encoding = TextEncoding.Utf8,
                        lineEnding = LineEnding.Lf,
                        hasBom = false,
                        isReadOnly = isLocked || !writable,
                        isModified = false,
                        activeMatchIndex = -1,
                        matchCount = 0,
                        isLoading = false,
                        title = note.title.ifBlank { displayName },
                        isMarkdown = true,
                        isLockedNote = isLocked,
                    )
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    _uiState.update { it.copy(isLoading = false, loadFailed = true) }
                }
                return@launch
            }

            val id = libraryFileId?.let(::FileId)?.takeIf { it.isWellFormed }
            this@TextDocViewModel.libraryFileId = id
            documentUri = uri
            try {
                val file = id?.let { files.get(it) }
                val target = uri ?: file?.let(files::contentUri)
                documentUri = target
                val name = displayName ?: file?.displayName
                val doc = if (target == null) {
                    TextDocument("", TextEncoding.Utf8, LineEnding.Lf, hasBom = false)
                } else {
                    uris.read(target).use { TextDocumentCodec.read(it) }
                }
                savedText = doc.text
                undoRedo.reset(doc.text)
                matches = emptyList()
                val isMd = MarkdownDetection.isMarkdown(name, mimeType)
                _uiState.value = TextDocUiState(
                    text = doc.text,
                    encoding = doc.encoding,
                    lineEnding = doc.lineEnding,
                    hasBom = doc.hasBom,
                    isReadOnly = doc.isReadOnly || !writable,
                    isModified = false,
                    activeMatchIndex = -1,
                    matchCount = 0,
                    isLoading = false,
                    title = name,
                    isMarkdown = isMd,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false, loadFailed = true) }
            }
        }
    }

    fun updateText(newText: String) {
        val state = _uiState.value
        if (state.isReadOnly || newText == state.text) return
        undoRedo.edit(newText)
        applyText(newText)
    }

    fun undo() {
        if (_uiState.value.isReadOnly) return
        undoRedo.undo()?.let(::applyText)
    }

    fun redo() {
        if (_uiState.value.isReadOnly) return
        undoRedo.redo()?.let(::applyText)
    }

    fun setReplaceQuery(value: String) = _uiState.update { it.copy(replaceQuery = value) }
    fun setMonospace(value: Boolean) = _uiState.update { it.copy(isMonospace = value) }
    fun setWrap(value: Boolean) = _uiState.update { it.copy(isWrap = value) }
    fun setHtmlPreview(value: Boolean) = _uiState.update { it.copy(isHtmlPreview = value) }
    fun setMarkdownPreview(value: Boolean) = _uiState.update { it.copy(isMarkdownPreview = value) }

    /** @return true when the file was written; false means the caller should offer Save as. */
    suspend fun saveInPlace(context: Context? = null): Boolean {
        val state = _uiState.value
        if (state.isReadOnly) return false
        val textSaved = state.text
        val noteId = currentNoteId
        if (noteId != null) {
            autosaveJob?.cancel()
            return try {
                _uiState.update { it.copy(isSaving = true) }
                notes.writeMarkdown(noteId, textSaved)
                savedText = textSaved
                _uiState.update { current ->
                    current.copy(isModified = current.text != textSaved, isSaving = false)
                }
                if (_uiState.value.text != textSaved) {
                    scheduleAutosave()
                }
                true
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                _uiState.update { it.copy(isSaving = false) }
                false
            }
        }
        val bytes = bytes(state)
        return try {
            _uiState.update { it.copy(isSaving = true) }
            val id = libraryFileId
            if (id != null) {
                files.updateContent(id) { bytes.inputStream() }
            } else {
                val target = documentUri ?: return false
                if (context == null) return false
                uris.write(target, bytes)
            }
            savedText = textSaved
            _uiState.update { current ->
                current.copy(isModified = current.text != textSaved, isSaving = false)
            }
            if (_uiState.value.text != textSaved) {
                scheduleAutosave()
            }
            true
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            _uiState.update { it.copy(isSaving = false) }
            false
        }
    }

    /** Writes a copy to [targetUri]; the editor continues with that copy. */
    suspend fun saveAs(context: Context? = null, targetUri: Uri): Boolean {
        val state = _uiState.value
        return try {
            if (context == null) return false
            _uiState.update { it.copy(isSaving = true) }
            uris.write(targetUri, bytes(state))
            documentUri = targetUri
            libraryFileId = null
            currentNoteId = null
            savedText = state.text
            _uiState.update { it.copy(isModified = false, isSaving = false, isReadOnly = false) }
            true
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            _uiState.update { it.copy(isSaving = false) }
            false
        }
    }

    fun find(query: String) {
        matches = FindReplace.matches(_uiState.value.text, query)
        _uiState.update {
            it.copy(
                findQuery = query,
                matchCount = matches.size,
                activeMatchIndex = if (matches.isEmpty()) -1 else 0,
            )
        }
    }

    fun nextMatch() = step(forward = true)
    fun previousMatch() = step(forward = false)

    fun replaceCurrent() {
        val state = _uiState.value
        if (state.isReadOnly) return
        val match = matches.getOrNull(state.activeMatchIndex) ?: return
        val text = FindReplace.replaceCurrent(state.text, match, state.replaceQuery)
        undoRedo.edit(text)
        applyText(text, keepIndex = state.activeMatchIndex)
    }

    fun replaceAll() {
        val state = _uiState.value
        if (state.isReadOnly || state.findQuery.isEmpty()) return
        val text = FindReplace.replaceAll(state.text, state.findQuery, state.replaceQuery)
        if (text == state.text) return
        undoRedo.edit(text)
        applyText(text, keepIndex = 0)
    }

    private fun step(forward: Boolean) {
        val state = _uiState.value
        val next = FindReplace.step(state.activeMatchIndex, matches.size, forward)
        _uiState.update { it.copy(activeMatchIndex = next) }
    }

    /** Applies a text change (typing, undo, replace) and refreshes the find hits. */
    private fun applyText(text: String, keepIndex: Int = _uiState.value.activeMatchIndex) {
        matches = FindReplace.matches(text, _uiState.value.findQuery)
        val modified = text != savedText
        _uiState.update {
            it.copy(
                text = text,
                isModified = modified,
                matchCount = matches.size,
                activeMatchIndex = when {
                    matches.isEmpty() -> -1
                    keepIndex in matches.indices -> keepIndex
                    else -> 0
                },
                canUndo = undoRedo.canUndo,
                canRedo = undoRedo.canRedo,
            )
        }
        if (modified) scheduleAutosave()
    }

    private fun scheduleAutosave() {
        val noteId = currentNoteId ?: return
        if (_uiState.value.isReadOnly) return
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(1000)
            if (currentNoteId == noteId && (_uiState.value.isModified || _uiState.value.text != savedText)) {
                saveInPlace()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        autosaveJob?.cancel()
    }

    private fun bytes(state: TextDocUiState): ByteArray =
        TextDocumentCodec.roundTripBytes(state.text, state.encoding, state.lineEnding, state.hasBom)
}

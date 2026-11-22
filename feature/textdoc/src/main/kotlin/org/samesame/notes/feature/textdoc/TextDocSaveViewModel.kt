package org.samesame.notes.feature.textdoc

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.samesame.notes.core.export.ExportFormat
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.files.LibraryFileTooLargeException
import org.samesame.notes.core.model.files.NotEnoughSpaceException
import org.samesame.notes.core.model.io.InputTooLargeException
import org.samesame.notes.core.model.io.LimitedInputStream
import org.samesame.notes.core.model.saveas.SaveAsOptions
import java.io.InputStream
import javax.inject.Inject

/** The document as the editor holds it when a Save option is picked. */
data class TextDocSaveSource(
    val text: String,
    val displayName: String?,
    val mimeType: String?,
    val markdown: Boolean,
    /** Edited since it was opened: copies are then made from [bytes], otherwise from [sourceUri] (the complete file). */
    val modified: Boolean,
    val sourceUri: Uri?,
    /** The text in the document's own encoding, line endings and byte-order mark. */
    val bytes: () -> ByteArray,
)

enum class TextDocSaveFailure {
    /** Over the note / rendering size cap, or the Files size cap. */
    TooLarge,
    NoSpace,
    /** The opened document can no longer be read. */
    Unreadable,
    /** The note, the Files copy or the picked file could not be written. */
    Failed,
}

sealed interface TextDocSaveEvent {
    data class NoteSaved(val noteId: String, val markdown: Boolean) : TextDocSaveEvent
    data class SavedToFiles(val name: String) : TextDocSaveEvent
    /** A copy or a rendered file was written to the picked location. */
    data object Saved : TextDocSaveEvent
    data class Failed(val reason: TextDocSaveFailure) : TextDocSaveEvent
}

/**
 * The text editor's Save options for a document opened from another app: Save as note (Markdown note for Markdown, text
 * note otherwise, text kept exactly), Save to Files, Save a copy in the original format, and PDF / HTML / Word renderings.
 * Kept apart from [TextDocViewModel] so the editing state is untouched. One operation at a time, off the main thread.
 */
@HiltViewModel
class TextDocSaveViewModel @Inject constructor(private val store: TextDocSaveStore) : ViewModel() {

    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _working = MutableStateFlow(false)
    /** True while a save runs: the screen shows a progress dialog. */
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private val eventChannel = Channel<TextDocSaveEvent>(Channel.BUFFERED)
    val events: Flow<TextDocSaveEvent> = eventChannel.receiveAsFlow()

    private var job: Job? = null

    fun saveAsNote(source: TextDocSaveSource) = launchSave {
        if (utf8Length(source.text) > Limits.MAX_TEXT_IMPORT_BYTES) return@launchSave TextDocSaveEvent.Failed(TextDocSaveFailure.TooLarge)
        val title = SaveAsOptions.noteTitle(source.displayName)
        val id = if (source.markdown) store.importMarkdown(source.text, title) else store.importText(source.text, title)
        TextDocSaveEvent.NoteSaved(id, source.markdown)
    }

    fun saveToFiles(source: TextDocSaveSource) = launchSave {
        val name = SaveAsOptions.suggestedName(source.displayName ?: defaultName(source))
        TextDocSaveEvent.SavedToFiles(store.saveToFiles(name, source.mimeType, contentOf(source)))
    }

    /** Writes the document in its own format to [target], a document the user just created with the file picker. */
    fun saveCopy(source: TextDocSaveSource, target: Uri) = launchSave(target) {
        contentOf(source)().use { input ->
            LimitedInputStream(input, Limits.MAX_LIBRARY_FILE_BYTES, "Document").use { limited ->
                store.openOutput(target).use { limited.copyTo(it) }
            }
        }
        TextDocSaveEvent.Saved
    }

    /** Renders the text as [format] (PDF, HTML or Word) into [target]. */
    fun export(source: TextDocSaveSource, format: ExportFormat, target: Uri) = launchSave(target) {
        if (utf8Length(source.text) > Limits.MAX_TEXT_IMPORT_BYTES) throw InputTooLargeException("Text too large to render")
        val title = SaveAsOptions.noteTitle(source.displayName)
        store.openOutput(target).use { out -> store.export(source.text, title, source.markdown, format, out) }
        TextDocSaveEvent.Saved
    }

    /** The suggested name of a rendering of [source] in [format]. */
    fun exportName(source: TextDocSaveSource, format: ExportFormat): String =
        SaveAsOptions.suggestedName(source.displayName ?: defaultName(source), extensionOf(format))

    fun copyName(source: TextDocSaveSource): String = SaveAsOptions.suggestedName(source.displayName ?: defaultName(source))

    private fun contentOf(source: TextDocSaveSource): () -> InputStream {
        val uri = source.sourceUri
        // An unchanged document is copied from the original, which also covers a file over the editor's size cap.
        return if (!source.modified && uri != null) {
            {
                try {
                    store.openInput(uri)
                } catch (e: SecurityException) {
                    throw UnreadableSourceException(e)
                } catch (e: java.io.IOException) {
                    throw UnreadableSourceException(e)
                }
            }
        } else {
            { source.bytes().inputStream() }
        }
    }

    /** Runs one save off the main thread; a failed write to [target] removes the half-written file. */
    private fun launchSave(target: Uri? = null, block: suspend () -> TextDocSaveEvent) {
        if (job?.isActive == true) return
        _working.value = true
        job = viewModelScope.launch {
            val event = try {
                withContext(ioDispatcher) { block() }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (target != null) withContext(ioDispatcher) { store.delete(target) }
                TextDocSaveEvent.Failed(failureOf(e))
            } finally {
                _working.value = false
            }
            eventChannel.send(event)
        }
    }

    private class UnreadableSourceException(cause: Throwable) : java.io.IOException(cause)

    internal companion object {
        fun failureOf(e: Throwable): TextDocSaveFailure = when (e) {
            is InputTooLargeException, is LibraryFileTooLargeException -> TextDocSaveFailure.TooLarge
            is NotEnoughSpaceException -> TextDocSaveFailure.NoSpace
            is UnreadableSourceException -> TextDocSaveFailure.Unreadable
            else -> TextDocSaveFailure.Failed
        }

        fun extensionOf(format: ExportFormat): String = when (format) {
            ExportFormat.PDF -> "pdf"
            ExportFormat.HTML -> "html"
            ExportFormat.DOCX -> "docx"
            else -> throw IllegalArgumentException("$format is not offered for text documents")
        }

        fun mimeOf(format: ExportFormat): String = when (format) {
            ExportFormat.PDF -> "application/pdf"
            ExportFormat.HTML -> "text/html"
            ExportFormat.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            else -> throw IllegalArgumentException("$format is not offered for text documents")
        }

        private fun defaultName(source: TextDocSaveSource) = if (source.markdown) "${SaveAsOptions.FALLBACK_NAME}.md" else "${SaveAsOptions.FALLBACK_NAME}.txt"

        private fun utf8Length(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()
    }
}

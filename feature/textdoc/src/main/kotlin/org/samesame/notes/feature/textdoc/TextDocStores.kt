package org.samesame.notes.feature.textdoc

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.repository.AppSettingsRepository
import org.samesame.notes.core.data.repository.FilesRepository
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.model.MarkdownEditMode
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.LibraryFile
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** The Files library operations the text editor needs. Separated so the view model is testable without the library. */
interface TextDocFileStore {
    suspend fun get(fileId: FileId): LibraryFile?
    fun contentUri(file: LibraryFile): Uri

    /** Replaces the stored copy; the caller's directory and name stay the same. */
    suspend fun updateContent(fileId: FileId, open: () -> InputStream): LibraryFile
}

/** Reading and writing a document URI picked by another app (ACTION_EDIT, ACTION_VIEW or Save as). */
interface TextDocUriStore {
    suspend fun read(uri: Uri): InputStream
    suspend fun write(uri: Uri, bytes: ByteArray)
}

/** Reading and writing a Markdown note through the notes repository. */
interface TextDocNoteStore {
    suspend fun readMarkdown(noteId: String): String?
    suspend fun writeMarkdown(noteId: String, text: String)
    suspend fun getNote(noteId: String): Note? = null
}

/** How a Markdown document opens: the Settings default, and the view each document was last left in. */
interface TextDocMarkdownModeStore {
    suspend fun defaultMode(): MarkdownEditMode
    suspend fun modeFor(documentKey: String): MarkdownViewMode?
    suspend fun setModeFor(documentKey: String, mode: MarkdownViewMode)
}

@Singleton
internal class DefaultTextDocFileStore @Inject constructor(private val repo: FilesRepository) : TextDocFileStore {
    override suspend fun get(fileId: FileId): LibraryFile? = repo.getFile(fileId)
    override fun contentUri(file: LibraryFile): Uri = repo.contentUri(file)
    override suspend fun updateContent(fileId: FileId, open: () -> InputStream): LibraryFile = repo.updateContent(fileId, open)
}

@Singleton
internal class DefaultTextDocUriStore @Inject constructor(@param:ApplicationContext private val context: Context) : TextDocUriStore {
    override suspend fun read(uri: Uri): InputStream = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Can't open $uri")
    }

    override suspend fun write(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var stream: OutputStream? = null
        try {
            stream = resolver.openOutputStream(uri, "wt")
        } catch (s: SecurityException) {
            throw s
        } catch (_: Exception) {
            // "wt" mode unsupported by provider; fallback to "w" with explicit truncation below
        }
        if (stream != null) {
            stream.use { it.write(bytes) }
            return@withContext
        }
        val pfd = try {
            resolver.openFileDescriptor(uri, "w")
        } catch (s: SecurityException) {
            throw s
        } ?: throw FileNotFoundException("Can't write $uri")

        pfd.use { fd ->
            val fos = FileOutputStream(fd.fileDescriptor)
            try {
                fos.channel.truncate(0)
            } catch (_: Exception) {
                // If channel truncation not supported
            }
            fos.use { it.write(bytes) }
        }
    }
}

@Singleton
internal class DefaultTextDocNoteStore @Inject constructor(private val notes: NoteRepository) : TextDocNoteStore {
    override suspend fun readMarkdown(noteId: String): String? = notes.readMarkdown(NoteId(noteId))
    override suspend fun writeMarkdown(noteId: String, text: String) = notes.writeMarkdown(NoteId(noteId), text)
    override suspend fun getNote(noteId: String): Note? = notes.getNote(NoteId(noteId))
}

/**
 * The Settings default comes from [AppSettingsRepository]; the per-document choice is a small private preferences
 * file keyed by a hash of the document (no names or paths stored), trimmed to the most recent [MAX_DOCUMENTS].
 */
@Singleton
internal class DefaultTextDocMarkdownModeStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: AppSettingsRepository,
) : TextDocMarkdownModeStore {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override suspend fun defaultMode(): MarkdownEditMode = settings.settings.first().markdownEditMode

    override suspend fun modeFor(documentKey: String): MarkdownViewMode? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(hash(documentKey), null) ?: return@withContext null
        val name = stored.substringBefore('|')
        MarkdownViewMode.entries.firstOrNull { it.name == name }
    }

    override suspend fun setModeFor(documentKey: String, mode: MarkdownViewMode) = withContext(Dispatchers.IO) {
        val key = hash(documentKey)
        val all = prefs.all
        prefs.edit {
            if (all.size >= MAX_DOCUMENTS && key !in all) {
                all.entries
                    .sortedBy { (it.value as? String)?.substringAfter('|')?.toLongOrNull() ?: 0L }
                    .take(all.size - MAX_DOCUMENTS + 1 + TRIM_EXTRA)
                    .forEach { remove(it.key) }
            }
            putString(key, "${mode.name}|${System.currentTimeMillis()}")
        }
    }

    private fun hash(documentKey: String): String =
        MessageDigest.getInstance("SHA-256").digest(documentKey.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PREFS = "textdoc_markdown_modes"
        const val MAX_DOCUMENTS = 500
        const val TRIM_EXTRA = 50
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TextDocModule {
    @Binds abstract fun fileStore(impl: DefaultTextDocFileStore): TextDocFileStore
    @Binds abstract fun uriStore(impl: DefaultTextDocUriStore): TextDocUriStore
    @Binds abstract fun noteStore(impl: DefaultTextDocNoteStore): TextDocNoteStore
    @Binds abstract fun markdownModeStore(impl: DefaultTextDocMarkdownModeStore): TextDocMarkdownModeStore
}

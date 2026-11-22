package org.samesame.notes.feature.textdoc

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.samesame.notes.core.data.repository.FilesRepository
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.data.transfer.FileConversionService
import org.samesame.notes.core.export.ExportFormat
import org.samesame.notes.core.model.files.DuplicatePolicy
import org.samesame.notes.core.model.files.ImportResult
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** What the text editor's Save options need from the notes, the Files library and the exporters. Faked in tests. */
interface TextDocSaveStore {
    /** A new Markdown note holding [text] exactly; returns its id. */
    suspend fun importMarkdown(text: String, title: String): String

    /** A new text note whose body is [text]; returns its id. */
    suspend fun importText(text: String, title: String): String

    /** Copies the stream into Files under [name] (a duplicate is kept as a second file); returns the stored name. */
    suspend fun saveToFiles(name: String, mimeType: String?, open: () -> InputStream): String

    /** Renders [text] as [format] into [out]. */
    suspend fun export(text: String, title: String, markdown: Boolean, format: ExportFormat, out: OutputStream)

    /** Opens a document the user picked or the app was given. */
    fun openInput(uri: Uri): InputStream

    /** Opens (and truncates) a document the user just created with the system file picker. */
    fun openOutput(uri: Uri): OutputStream

    /** Removes a document this app created that could not be written completely; never throws. */
    fun delete(uri: Uri)
}

@Singleton
internal class DefaultTextDocSaveStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notes: NoteRepository,
    private val files: FilesRepository,
    private val conversion: FileConversionService,
) : TextDocSaveStore {
    override suspend fun importMarkdown(text: String, title: String): String = notes.importMarkdown(text, title).value
    override suspend fun importText(text: String, title: String): String = notes.importText(text, title).value

    override suspend fun saveToFiles(name: String, mimeType: String?, open: () -> InputStream): String =
        when (val r = files.importStream(open, name, folderId = null, declaredMime = mimeType, onDuplicate = DuplicatePolicy.KeepBoth)) {
            is ImportResult.Imported -> r.file.displayName
            is ImportResult.Skipped -> r.existing.displayName
            is ImportResult.Duplicate -> r.existing.displayName
        }

    override suspend fun export(text: String, title: String, markdown: Boolean, format: ExportFormat, out: OutputStream) {
        conversion.exportText(text, title, markdown, format, out)
    }

    override fun openInput(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Can't open the document")

    override fun openOutput(uri: Uri): OutputStream {
        val resolver = context.contentResolver
        // Some providers reject "wt"; the document was just created empty, so plain "w" is the same there.
        val stream = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: resolver.openOutputStream(uri, "w")
        return stream ?: throw FileNotFoundException("Can't write the document")
    }

    override fun delete(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TextDocSaveModule {
    @Binds abstract fun saveStore(impl: DefaultTextDocSaveStore): TextDocSaveStore
}

package org.samesame.notes.feature.textdoc

import android.content.Context
import android.net.Uri
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.repository.FilesRepository
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.LibraryFile
import java.io.FileNotFoundException
import java.io.InputStream
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
        // "wt" truncates; a provider that only knows "w" still gets the whole buffer, which is at least as long as
        // anything we wrote before only when it grows. Both are attempted so read-only providers fail with SecurityException.
        val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: runCatching { resolver.openOutputStream(uri, "w") }.getOrNull()
            ?: throw FileNotFoundException("Can't write $uri")
        out.use { it.write(bytes) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TextDocModule {
    @Binds abstract fun fileStore(impl: DefaultTextDocFileStore): TextDocFileStore
    @Binds abstract fun uriStore(impl: DefaultTextDocUriStore): TextDocUriStore
}

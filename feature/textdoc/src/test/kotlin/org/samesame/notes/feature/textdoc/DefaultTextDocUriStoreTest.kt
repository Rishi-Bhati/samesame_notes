package org.samesame.notes.feature.textdoc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultTextDocUriStoreTest {

    private val context get() = RuntimeEnvironment.getApplication()

    class FallbackTruncationProvider : ContentProvider() {
        companion object {
            lateinit var targetFile: File
            var throwSecurityException = false
        }

        override fun onCreate(): Boolean = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (throwSecurityException) {
                throw SecurityException("Permission denied")
            }
            if (mode == "wt") {
                throw IllegalArgumentException("Mode wt not supported by provider")
            }
            return ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_WRITE)
        }

        override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String? = "text/plain"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, s: String?, sa: Array<out String>?): Int = 0
    }

    @Test
    fun writeTruncatesWhenProviderRejectsWtMode() = runTest {
        val file = File.createTempFile("truncate_test", ".txt").apply {
            writeText("This is long initial text that must be truncated away completely.")
        }
        FallbackTruncationProvider.targetFile = file
        FallbackTruncationProvider.throwSecurityException = false

        val authority = "org.samesame.notes.test.truncation"
        Robolectric.buildContentProvider(FallbackTruncationProvider::class.java).create(authority)

        val uri = Uri.parse("content://$authority/doc")
        val store = DefaultTextDocUriStore(context)

        store.write(uri, "Short text".toByteArray())

        assertThat(file.readText()).isEqualTo("Short text")
        file.delete()
    }

    @Test
    fun writeDoesNotSwallowSecurityException() = runTest {
        FallbackTruncationProvider.targetFile = File.createTempFile("sec_test", ".txt")
        FallbackTruncationProvider.throwSecurityException = true

        val authority = "org.samesame.notes.test.security"
        Robolectric.buildContentProvider(FallbackTruncationProvider::class.java).create(authority)

        val uri = Uri.parse("content://$authority/doc")
        val store = DefaultTextDocUriStore(context)

        assertThrows(SecurityException::class.java) {
            kotlinx.coroutines.runBlocking {
                store.write(uri, "New text".toByteArray())
            }
        }
        FallbackTruncationProvider.targetFile.delete()
    }
}

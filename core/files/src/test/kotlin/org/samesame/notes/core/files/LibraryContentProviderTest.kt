package org.samesame.notes.core.files

import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.OpenableColumns
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.samesame.notes.core.files.provider.LibraryContentProvider
import org.samesame.notes.core.files.provider.LibraryFileLookup
import org.samesame.notes.core.files.provider.LibraryProviderContract
import org.samesame.notes.core.files.provider.ProvidedFile
import org.samesame.notes.core.model.files.FileId
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryContentProviderTest {
    @get:Rule val tmp = TemporaryFolder()

    class TestProvider : LibraryContentProvider() {
        override fun lookup(): LibraryFileLookup = LibraryFileLookup { files[it] }
        companion object { val files = HashMap<String, ProvidedFile>() }
    }

    private val authority = "org.samesame.notes.test.files"
    private val id = FileId("0f8fad5b-d9cb-469f-a165-70867728950e")
    private lateinit var provider: TestProvider

    @Before fun setUp() {
        val blob = tmp.newFile("blob").apply { writeText("content") }
        TestProvider.files.clear()
        TestProvider.files[id.value] = ProvidedFile("Report.pdf", "application/pdf", 7, blob)
        provider = Robolectric.buildContentProvider(TestProvider::class.java)
            .create(ProviderInfo().apply { this.authority = this@LibraryContentProviderTest.authority; grantUriPermissions = true })
            .get()
    }

    private val uri get() = LibraryProviderContract.uriFor(authority, id, "Report.pdf")

    @Test fun `serves name, size, type and read-only content`() {
        assertThat(uri.toString()).isEqualTo("content://$authority/f/${id.value}/Report.pdf")
        assertThat(provider.getType(uri)).isEqualTo("application/pdf")
        provider.query(uri, null, null, null, null)!!.use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))).isEqualTo("Report.pdf")
            assertThat(c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE))).isEqualTo(7)
        }
        provider.query(uri, arrayOf(OpenableColumns.SIZE, "_data"), null, null, null)!!.use { c ->
            assertThat(c.columnNames.toList()).containsExactly(OpenableColumns.SIZE)
        }
        provider.openFile(uri, "r").use { pfd ->
            assertThat(java.io.FileInputStream(pfd.fileDescriptor).readBytes().decodeToString()).isEqualTo("content")
        }
    }

    @Test fun `the name segment is only a label`() {
        val other = LibraryProviderContract.uriFor(authority, id, "../../anything.exe")
        assertThat(provider.getType(other)).isEqualTo("application/pdf")
    }

    @Test fun `writes, bad ids and unknown files are refused`() {
        assertThrows(SecurityException::class.java) { provider.openFile(uri, "w") }
        assertThrows(SecurityException::class.java) { provider.openFile(uri, "rw") }
        assertThrows(UnsupportedOperationException::class.java) { provider.delete(uri, null, null) }
        assertThrows(UnsupportedOperationException::class.java) { provider.insert(uri, null) }
        val bad = Uri.parse("content://$authority/f/..%2F..%2Fdatabases/x")
        assertThrows(FileNotFoundException::class.java) { provider.openFile(bad, "r") }
        assertThat(provider.query(bad, null, null, null, null)).isNull()
        val unknown = LibraryProviderContract.uriFor(authority, FileId("7c9e6679-7425-40de-944b-e07fc1f90ae7"), "x")
        assertThrows(FileNotFoundException::class.java) { provider.openFile(unknown, "r") }
        assertThat(provider.getType(unknown)).isNull()
    }

    @Test fun `missing content is not found`() {
        TestProvider.files[id.value]!!.blob.delete()
        assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "r") }
    }
}

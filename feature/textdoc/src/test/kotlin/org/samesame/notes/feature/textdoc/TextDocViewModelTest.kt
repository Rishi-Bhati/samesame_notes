package org.samesame.notes.feature.textdoc

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.LibraryFile
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextDocViewModelTest {

    private val file = LibraryFile(
        id = FileId("11111111-1111-1111-1111-111111111111"),
        folderId = null,
        displayName = "notes.txt",
        ext = "txt",
        mimeType = "text/plain",
        category = FileCategory.Text,
        sizeBytes = 5,
        sha256 = "a".repeat(64),
        createdAt = 0,
        modifiedAt = 0,
    )
    private val files = FakeFileStore(file, "hello")
    private val uris = FakeUriStore("hello")
    private val context = RuntimeEnvironment.getApplication()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsAndSavesALibraryFileInPlace() = runTest {
        val vm = TextDocViewModel(files, uris)
        vm.load(null, file.id.value, writable = true)

        assertThat(vm.uiState.value.text).isEqualTo("hello")
        assertThat(vm.uiState.value.isReadOnly).isFalse()

        vm.updateText("hello world")
        assertThat(vm.uiState.value.isModified).isTrue()
        assertThat(vm.saveInPlace(context)).isTrue()

        assertThat(String(files.updated!!)).isEqualTo("hello world")
        assertThat(vm.uiState.value.isModified).isFalse()
    }

    @Test
    fun undoAndRedoTrackModificationState() = runTest {
        val vm = TextDocViewModel(files, uris)
        vm.load(null, file.id.value, writable = true)

        vm.updateText("hello!")
        vm.undo()
        assertThat(vm.uiState.value.text).isEqualTo("hello")
        assertThat(vm.uiState.value.isModified).isFalse()

        vm.redo()
        assertThat(vm.uiState.value.text).isEqualTo("hello!")
        assertThat(vm.uiState.value.isModified).isTrue()
    }

    @Test
    fun anExternalUriThatCannotBeWrittenFallsBackToSaveAs() = runTest {
        val source = Uri.parse("content://test/read-only.txt")
        uris.failWrite = true
        val vm = TextDocViewModel(files, uris)
        vm.load(source, null, writable = true)
        vm.updateText("changed")

        assertThat(vm.saveInPlace(context)).isFalse()

        val target = Uri.parse("content://test/copy.txt")
        uris.failWrite = false
        assertThat(vm.saveAs(context, target)).isTrue()
        assertThat(String(uris.written!!)).isEqualTo("changed")
        assertThat(vm.uiState.value.isModified).isFalse()
    }

    @Test
    fun aReadOnlyDocumentCannotBeEditedOrSavedInPlace() = runTest {
        val vm = TextDocViewModel(files, uris)
        vm.load(null, file.id.value, writable = false)

        vm.updateText("changed")
        assertThat(vm.uiState.value.text).isEqualTo("hello")
        assertThat(vm.saveInPlace(context)).isFalse()
    }

    @Test
    fun htmlPreviewToggles() = runTest {
        val vm = TextDocViewModel(files, uris)
        vm.load(null, file.id.value, writable = true)

        assertThat(vm.uiState.value.isHtmlPreview).isFalse()
        vm.setHtmlPreview(true)
        assertThat(vm.uiState.value.isHtmlPreview).isTrue()
    }

    @Test
    fun findAndReplaceUpdatesTheDocument() = runTest {
        val vm = TextDocViewModel(files, uris)
        vm.load(null, file.id.value, writable = true)

        vm.find("l")
        assertThat(vm.uiState.value.matchCount).isEqualTo(2)
        vm.setReplaceQuery("L")
        vm.replaceAll()

        assertThat(vm.uiState.value.text).isEqualTo("heLLo")
        assertThat(vm.uiState.value.isModified).isTrue()
    }

    private class FakeFileStore(private val file: LibraryFile, var content: String) : TextDocFileStore {
        var updated: ByteArray? = null

        override suspend fun get(fileId: FileId) = file.takeIf { it.id == fileId }
        override fun contentUri(file: LibraryFile): Uri = Uri.parse("content://test/library/${file.id.value}")
        override suspend fun updateContent(fileId: FileId, open: () -> InputStream): LibraryFile {
            updated = open().use { it.readBytes() }
            content = String(updated!!)
            return file.copy(sizeBytes = updated!!.size.toLong())
        }
    }

    private class FakeUriStore(var data: String) : TextDocUriStore {
        var failWrite = false
        var written: ByteArray? = null

        override suspend fun read(uri: Uri): InputStream = data.byteInputStream()
        override suspend fun write(uri: Uri, bytes: ByteArray) {
            if (failWrite) throw SecurityException("read-only")
            written = bytes
        }
    }
}

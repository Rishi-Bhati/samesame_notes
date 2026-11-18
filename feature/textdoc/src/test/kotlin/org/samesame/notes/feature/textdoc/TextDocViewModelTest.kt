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
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.NoteKind
import org.samesame.notes.core.model.PageMode
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
    private val noteStore = FakeNoteStore()
    private val context = RuntimeEnvironment.getApplication()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsAndSavesALibraryFileInPlace() = runTest {
        val vm = TextDocViewModel(files, uris, noteStore)
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
        val vm = TextDocViewModel(files, uris, noteStore)
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
        val vm = TextDocViewModel(files, uris, noteStore)
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
        val vm = TextDocViewModel(files, uris, noteStore)
        vm.load(null, file.id.value, writable = false)

        vm.updateText("changed")
        assertThat(vm.uiState.value.text).isEqualTo("hello")
        assertThat(vm.saveInPlace(context)).isFalse()
    }

    @Test
    fun htmlPreviewToggles() = runTest {
        val vm = TextDocViewModel(files, uris, noteStore)
        vm.load(null, file.id.value, writable = true)

        assertThat(vm.uiState.value.isHtmlPreview).isFalse()
        vm.setHtmlPreview(true)
        assertThat(vm.uiState.value.isHtmlPreview).isTrue()
    }

    @Test
    fun findAndReplaceUpdatesTheDocument() = runTest {
        val vm = TextDocViewModel(files, uris, noteStore)
        vm.load(null, file.id.value, writable = true)

        vm.find("l")
        assertThat(vm.uiState.value.matchCount).isEqualTo(2)
        vm.setReplaceQuery("L")
        vm.replaceAll()

        assertThat(vm.uiState.value.text).isEqualTo("heLLo")
        assertThat(vm.uiState.value.isModified).isTrue()
    }

    @Test
    fun loadsAndEditsMarkdownNote() = runTest {
        val note = sampleNote(id = "md-note", title = "Shopping list")
        noteStore.note = note
        noteStore.text = "- [ ] milk\n- [ ] eggs\n"

        val vm = TextDocViewModel(files, uris, noteStore)
        vm.load(noteId = "md-note", writable = true)

        assertThat(vm.uiState.value.text).isEqualTo("- [ ] milk\n- [ ] eggs\n")
        assertThat(vm.uiState.value.title).isEqualTo("Shopping list")
        assertThat(vm.uiState.value.isMarkdown).isTrue()
        assertThat(vm.uiState.value.isReadOnly).isFalse()
        assertThat(vm.uiState.value.isModified).isFalse()

        vm.updateText("- [x] milk\n- [ ] eggs\n")
        assertThat(vm.uiState.value.isModified).isTrue()
        assertThat(vm.saveInPlace()).isTrue()
        assertThat(noteStore.text).isEqualTo("- [x] milk\n- [ ] eggs\n")
        assertThat(vm.uiState.value.isModified).isFalse()
    }

    @Test
    fun lockedMarkdownNoteOpensReadOnlyWithNotice() = runTest {
        val note = sampleNote(id = "locked-md", title = "Secret note", isLocked = true)
        noteStore.note = note
        noteStore.text = "secret content"

        val vm = TextDocViewModel(files, uris, noteStore)
        vm.load(noteId = "locked-md", writable = true)

        assertThat(vm.uiState.value.title).isEqualTo("Secret note")
        assertThat(vm.uiState.value.isReadOnly).isTrue()
        assertThat(vm.uiState.value.isLockedNote).isTrue()
        assertThat(vm.uiState.value.isMarkdown).isTrue()
        assertThat(vm.uiState.value.text).isEmpty()

        // Edits should be rejected
        vm.updateText("try edit")
        assertThat(vm.uiState.value.text).isEmpty()
    }

    @Test
    fun noteAutosaveDebouncesAndWritesOnce() = runTest {
        val note = sampleNote(id = "debounced-note", title = "Notes")
        noteStore.note = note
        noteStore.text = "initial text"

        val vm = TextDocViewModel(files, uris, noteStore)
        vm.load(noteId = "debounced-note", writable = true)

        assertThat(noteStore.writeCount).isEqualTo(0)

        // First edit
        vm.updateText("first edit")
        assertThat(noteStore.writeCount).isEqualTo(0)

        // Advance 500 ms (debounce is 1000 ms)
        testScheduler.advanceTimeBy(500)
        assertThat(noteStore.writeCount).isEqualTo(0)

        // Second edit within debounce period
        vm.updateText("second edit")
        assertThat(noteStore.writeCount).isEqualTo(0)

        // Advance 500 ms from second edit (total 1000 ms from first, but only 500 ms from second)
        testScheduler.advanceTimeBy(500)
        assertThat(noteStore.writeCount).isEqualTo(0)

        // Advance remaining 501 ms (debounce expires)
        testScheduler.advanceTimeBy(501)
        assertThat(noteStore.writeCount).isEqualTo(1)
        assertThat(noteStore.lastWrittenText).isEqualTo("second edit")
        assertThat(vm.uiState.value.isModified).isFalse()
    }

    private fun sampleNote(
        id: String = "note-1",
        title: String = "My Markdown Note",
        isLocked: Boolean = false,
        kind: NoteKind = NoteKind.Markdown,
    ) = Note(
        id = NoteId(id),
        title = title,
        previewText = "",
        folderId = null,
        isFavorite = false,
        isLocked = isLocked,
        pageMode = PageMode.Infinite,
        backgroundColor = 0,
        createdAt = 0,
        modifiedAt = 0,
        lastOpenedAt = 0,
        trashedAt = null,
        kind = kind,
    )

    private class FakeNoteStore(
        var note: Note? = null,
        var text: String? = null,
    ) : TextDocNoteStore {
        var writeCount = 0
        var lastWrittenText: String? = null

        override suspend fun readMarkdown(noteId: String): String? = text
        override suspend fun writeMarkdown(noteId: String, text: String) {
            this.text = text
            this.lastWrittenText = text
            writeCount++
        }
        override suspend fun getNote(noteId: String): Note? = note
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

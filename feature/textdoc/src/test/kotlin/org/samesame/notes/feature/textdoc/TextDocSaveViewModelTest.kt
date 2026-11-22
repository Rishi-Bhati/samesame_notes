package org.samesame.notes.feature.textdoc

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.samesame.notes.core.export.ExportFormat
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.files.NotEnoughSpaceException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextDocSaveViewModelTest {
    private val store = FakeSaveStore()
    private val source = Uri.parse("content://other.app/doc/1")
    private val target = Uri.parse("content://picker/doc/new")

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = TextDocSaveViewModel(store).apply { ioDispatcher = Dispatchers.Main }

    private fun doc(
        text: String = "# Hi\n\n- a\n",
        name: String? = "Plan.md",
        markdown: Boolean = true,
        modified: Boolean = false,
        bytes: ByteArray = text.toByteArray(),
    ) = TextDocSaveSource(text, name, "text/markdown", markdown, modified, source) { bytes }

    @Test fun markdownBecomesAMarkdownNoteWithTheTextKeptExactly() = runTest {
        val vm = vm()
        val text = "# Title\r\n\n  * item  \n<b>raw</b>\n"
        vm.saveAsNote(doc(text = text))
        assertThat(vm.events.first()).isEqualTo(TextDocSaveEvent.NoteSaved("md-1", markdown = true))
        assertThat(store.markdownNotes).containsExactly("Plan" to text)
        assertThat(store.textNotes).isEmpty()
    }

    @Test fun plainTextBecomesATextNote() = runTest {
        val vm = vm()
        vm.saveAsNote(doc(text = "a,b\n1,2\n", name = "table.csv", markdown = false))
        assertThat(vm.events.first()).isEqualTo(TextDocSaveEvent.NoteSaved("txt-1", markdown = false))
        assertThat(store.textNotes).containsExactly("table" to "a,b\n1,2\n")
    }

    @Test fun textOverTheNoteCapIsRefused() = runTest {
        val vm = vm()
        vm.saveAsNote(doc(text = "x".repeat(Limits.MAX_TEXT_IMPORT_BYTES.toInt() + 1)))
        assertThat(vm.events.first()).isEqualTo(TextDocSaveEvent.Failed(TextDocSaveFailure.TooLarge))
        assertThat(store.markdownNotes).isEmpty()
    }

    @Test fun anUnchangedDocumentIsCopiedFromTheOriginal() = runTest {
        val vm = vm()
        store.original = "original bytes, even past the editor cap".toByteArray()
        vm.saveToFiles(doc(modified = false, bytes = "editor".toByteArray()))
        assertThat(vm.events.first()).isEqualTo(TextDocSaveEvent.SavedToFiles("Plan.md"))
        assertThat(store.filesSaved.single().second).isEqualTo(store.original)
    }

    @Test fun anEditedDocumentIsSavedAsEdited() = runTest {
        val vm = vm()
        vm.saveCopy(doc(modified = true, bytes = "edited".toByteArray()), target)
        assertThat(vm.events.first()).isEqualTo(TextDocSaveEvent.Saved)
        assertThat(store.written[target]!!.toString(Charsets.UTF_8)).isEqualTo("edited")
    }

    @Test fun theFilesNameIsSanitised() = runTest {
        val vm = vm()
        vm.saveToFiles(doc(name = "../../etc/passwd\u0000.md"))
        vm.events.first()
        assertThat(store.filesSaved.single().first).doesNotContain("/")
        assertThat(store.filesSaved.single().first).doesNotContain("\u0000")
    }

    @Test fun noSpaceIsReported() = runTest {
        val vm = vm()
        store.filesError = NotEnoughSpaceException("full")
        vm.saveToFiles(doc())
        assertThat(vm.events.first()).isEqualTo(TextDocSaveEvent.Failed(TextDocSaveFailure.NoSpace))
    }

    @Test fun exportWritesTheRenderingAndAFailedOneIsRemoved() = runTest {
        val vm = vm()
        vm.export(doc(), ExportFormat.PDF, target)
        assertThat(vm.events.first()).isEqualTo(TextDocSaveEvent.Saved)
        assertThat(store.exports).containsExactly(Triple("Plan", true, ExportFormat.PDF))
        assertThat(store.written[target]!!.toString(Charsets.UTF_8)).isEqualTo("PDF:# Hi\n\n- a\n")

        store.exportError = IOException("render failed")
        vm.export(doc(), ExportFormat.HTML, target)
        assertThat(vm.events.first()).isEqualTo(TextDocSaveEvent.Failed(TextDocSaveFailure.Failed))
        assertThat(store.deleted).containsExactly(target)
        assertThat(vm.working.value).isFalse()
    }

    @Test fun anUnreadableOriginalIsReported() = runTest {
        val vm = vm()
        store.original = null
        vm.saveCopy(doc(modified = false), target)
        assertThat(vm.events.first()).isEqualTo(TextDocSaveEvent.Failed(TextDocSaveFailure.Unreadable))
    }

    @Test fun suggestedNamesFollowTheFormat() {
        val vm = vm()
        assertThat(vm.exportName(doc(), ExportFormat.PDF)).isEqualTo("Plan.pdf")
        assertThat(vm.exportName(doc(name = null, markdown = false), ExportFormat.DOCX)).isEqualTo("Document.docx")
        assertThat(vm.copyName(doc(name = null))).isEqualTo("Document.md")
        assertThat(TextDocSaveViewModel.mimeOf(ExportFormat.HTML)).isEqualTo("text/html")
    }
}

private class FakeSaveStore : TextDocSaveStore {
    val markdownNotes = ArrayList<Pair<String, String>>()
    val textNotes = ArrayList<Pair<String, String>>()
    val filesSaved = ArrayList<Pair<String, ByteArray>>()
    val exports = ArrayList<Triple<String, Boolean, ExportFormat>>()
    val written = HashMap<Uri, ByteArrayOutputStream>()
    val deleted = ArrayList<Uri>()
    var original: ByteArray? = "original".toByteArray()
    var filesError: Exception? = null
    var exportError: Exception? = null

    override suspend fun importMarkdown(text: String, title: String): String {
        markdownNotes += title to text
        return "md-${markdownNotes.size}"
    }

    override suspend fun importText(text: String, title: String): String {
        textNotes += title to text
        return "txt-${textNotes.size}"
    }

    override suspend fun saveToFiles(name: String, mimeType: String?, open: () -> InputStream): String {
        filesError?.let { throw it }
        filesSaved += name to open().use { it.readBytes() }
        return name
    }

    override suspend fun export(text: String, title: String, markdown: Boolean, format: ExportFormat, out: OutputStream) {
        exportError?.let { throw it }
        exports += Triple(title, markdown, format)
        out.write("$format:$text".toByteArray())
    }

    override fun openInput(uri: Uri): InputStream = (original ?: throw IOException("gone")).inputStream()

    override fun openOutput(uri: Uri): OutputStream = ByteArrayOutputStream().also { written[uri] = it }

    override fun delete(uri: Uri) {
        deleted += uri
    }
}

package org.samesame.notes.core.data.transfer

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.export.ExportFormat
import org.samesame.notes.core.export.ExportTarget
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.PageMode
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.zip.ZipInputStream

class NoteTransferServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun note(id: String, title: String, locked: Boolean = false) = Note(
        NoteId(id), title, "", null, false, locked, PageMode.Pages, 0, 0, 0, 0, null,
    )

    /** Only `observeNote` and `exportNote` are implemented; everything else throws. */
    private fun repo(notes: List<Note>, failing: Set<String> = emptySet()): NoteRepository =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(NoteRepository::class.java)) { _, method, args ->
            try {
                when (method.name.substringBefore('-')) {
                    "observeNote" -> { val id = NoteId(args[0].toString()); flowOf(notes.firstOrNull { it.id == id }) as Flow<Note?> }
                    "exportNote" -> {
                        val id = NoteId(args[0].toString()) // inline class: passed unboxed
                        val format = args[1] as ExportFormat
                        val out = args[3] as OutputStream
                        if (id.value in failing) throw java.io.IOException("boom")
                        out.write("content of ${id.value}".toByteArray())
                        ExportTarget(if (format == ExportFormat.PDF) "pdf" else "txt", "application/x-test")
                    }
                    else -> throw UnsupportedOperationException(method.name)
                }
            } catch (e: InvocationTargetException) { throw e.targetException }
        } as NoteRepository

    @Test
    fun skipsLockedNotesAndNumbersDuplicateTitles() = runBlocking<Unit> {
        val notes = listOf(note("a", "Plan"), note("b", "Plan"), note("c", "Secret", locked = true), note("d", ""))
        val service = NoteTransferService(repo(notes), tmp.newFolder("share"))
        val batch = service.exportToCache(notes.map { it.id }, ExportFormat.PDF)
        assertThat(batch.skippedLocked).isEqualTo(1)
        assertThat(batch.failed).isEqualTo(0)
        assertThat(batch.items.map { it.name }).containsExactly("Plan.pdf", "Plan (1).pdf", "Note.pdf").inOrder()
        assertThat(batch.items[1].file.readText()).isEqualTo("content of b")
        assertThat(batch.items[0].mimeType).isEqualTo("application/x-test")
    }

    @Test
    fun aFailingNoteIsCountedAndLeavesNoTempFile() = runBlocking<Unit> {
        val notes = listOf(note("a", "Good"), note("b", "Bad"))
        val root = tmp.newFolder("share")
        val service = NoteTransferService(repo(notes, failing = setOf("b")), root)
        val batch = service.exportToCache(notes.map { it.id }, ExportFormat.TXT)
        assertThat(batch.items.map { it.name }).containsExactly("Good.txt")
        assertThat(batch.failed).isEqualTo(1)
        assertThat(root.walkTopDown().filter { it.isFile }.map { it.name }.toList()).containsExactly("Good.txt")
    }

    @Test
    fun nextRunReplacesThePreviousFiles() = runBlocking<Unit> {
        val notes = listOf(note("a", "One"))
        val service = NoteTransferService(repo(notes), tmp.newFolder("share"))
        val first = service.exportToCache(listOf(NoteId("a")), ExportFormat.TXT)
        service.exportToCache(listOf(NoteId("a")), ExportFormat.TXT)
        assertThat(first.items.single().file.exists()).isFalse()
    }

    @Test
    fun zipHoldsEveryItemUnderItsName() = runBlocking<Unit> {
        val notes = listOf(note("a", "Plan"), note("b", "Plan"))
        val service = NoteTransferService(repo(notes), tmp.newFolder("share"))
        val batch = service.exportToCache(notes.map { it.id }, ExportFormat.TXT)
        val bytes = java.io.ByteArrayOutputStream().also { service.writeZip(batch, it) }.toByteArray()
        val entries = LinkedHashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes().decodeToString() }
        }
        assertThat(entries).containsExactly("Plan.txt", "content of a", "Plan (1).txt", "content of b").inOrder()
    }

    @Test
    fun bulkExportDeliversEachNoteSkipsLockedCountsFailuresAndCleansUp() = runBlocking<Unit> {
        val notes = listOf(note("a", "Plan"), note("b", "Plan"), note("c", "Secret", locked = true), note("d", "Bad"))
        val root = tmp.newFolder("share")
        val service = NoteTransferService(repo(notes, failing = setOf("d")), root)
        val delivered = LinkedHashMap<String, String>()
        val progress = ArrayList<Int>()
        val result = service.exportEach(notes.map { it.id } + NoteId("missing"), ExportFormat.PDF, onProgress = { done, _, _ -> progress += done }) {
            delivered[it.name] = it.file.readText()
        }
        assertThat(result).isEqualTo(BulkExportResult(exported = 2, skippedLocked = 1, failed = 2))
        assertThat(delivered).containsExactly("Plan.pdf", "content of a", "Plan (1).pdf", "content of b").inOrder()
        assertThat(progress.last()).isEqualTo(5)
        assertThat(root.walkTopDown().filter { it.isFile }.toList()).isEmpty()
    }

    @Test
    fun aFailedDeliveryCountsAsFailed() = runBlocking<Unit> {
        val notes = listOf(note("a", "One"), note("b", "Two"))
        val service = NoteTransferService(repo(notes), tmp.newFolder("share"))
        val result = service.exportEach(notes.map { it.id }, ExportFormat.TXT) { if (it.noteId.value == "a") throw java.io.IOException("disk full") }
        assertThat(result).isEqualTo(BulkExportResult(exported = 1, skippedLocked = 0, failed = 1))
    }
}

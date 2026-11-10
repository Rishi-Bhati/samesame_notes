package org.samesame.notes.core.data.repository

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.samesame.notes.core.data.Clock
import org.samesame.notes.core.database.SameSameDatabase
import org.samesame.notes.core.document.NoteSnapshot
import org.samesame.notes.core.document.NoteDocumentStore
import org.samesame.notes.core.export.NoteExporter
import org.samesame.notes.core.files.BlobStore
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.files.DuplicatePolicy
import org.samesame.notes.core.model.files.FileLinks
import org.samesame.notes.core.model.files.FileRefKind
import org.samesame.notes.core.model.files.ImportResult
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextRun
import java.io.ByteArrayInputStream

/** Files phase F-3: saving a note rebuilds its backlinks (`file_refs`), which the file's Info lists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteFileBacklinksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = Clock { 1_000_000L }
    private lateinit var db: SameSameDatabase
    private lateinit var documents: NoteDocumentStore
    private lateinit var files: DefaultFilesRepository
    private lateinit var notes: NoteRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SameSameDatabase::class.java).build()
        documents = NoteDocumentStore(tmp.newFolder("notes"), tmp.newFolder("working"))
        files = DefaultFilesRepository(
            RuntimeEnvironment.getApplication(), db.libraryDao(), BlobStore(tmp.newFolder("library"), freeSpace = { Long.MAX_VALUE / 2 }), clock,
        )
        val settings = object : AppSettingsRepository {
            override val settings: Flow<AppSettings> = flowOf(AppSettings())
            override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
        }
        notes = DefaultNoteRepository(
            db.noteDao(), db.searchDao(), db.viewStateDao(), documents, clock, NoteExporter(), settings,
            PreviewFailureStore(tmp.newFolder("previews")),
            org.samesame.notes.core.database.DatabaseMaintenance(db),
            org.samesame.notes.core.data.transfer.ShareCache(tmp.newFolder("share")),
            fileRefs = files,
        )
    }

    @After fun tearDown() = db.close()

    private suspend fun file(name: String): LibraryFile =
        (files.importStream({ ByteArrayInputStream(name.toByteArray()) }, name, null, onDuplicate = DuplicatePolicy.KeepBoth) as ImportResult.Imported).file

    @Test fun `saving a note indexes its links and shown pictures, and removing them clears the rows`() = runBlocking<Unit> {
        val pdf = file("plan.pdf")
        val pic = file("photo.png")
        val id = notes.createNote(null)
        val uri = FileLinks.format(pdf)
        val text = "plan.pdf $OBJECT_CHAR"
        val body = RichText(text, listOf(TextRun(8, CharStyle(link = uri)), TextRun(1, CharStyle()), TextRun(1, CharStyle(objectId = "img"))))
        val doc = documents.readDocument(id.value).copy(
            title = "Trip", body = body,
            inlineObjects = listOf(ImageObject("img", "media/x.png", 10f, 10f, libraryFileId = pic.id.value)),
        )
        notes.saveContent(id, NoteSnapshot(doc))

        val pdfUse = files.observeUsage(pdf.id).first().single()
        assertThat(pdfUse.noteId).isEqualTo(id)
        assertThat(pdfUse.noteTitle).isEqualTo("Trip")
        assertThat(pdfUse.kinds).containsExactly(FileRefKind.Link)
        assertThat(files.observeUsage(pic.id).first().single().kinds).containsExactly(FileRefKind.Inline)
        assertThat(files.usageCount(listOf(pdf.id, pic.id))).isEqualTo(1)

        notes.saveContent(id, NoteSnapshot(doc.copy(body = RichText.of("nothing linked"))))
        assertThat(files.observeUsage(pdf.id).first()).isEmpty()
        assertThat(files.observeUsage(pic.id).first()).isEmpty()
    }

    @Test fun `a note without files writes no rows`() = runBlocking<Unit> {
        val pdf = file("a.pdf")
        val id = notes.createNote(null)
        notes.saveContent(id, NoteSnapshot(documents.readDocument(id.value).copy(body = RichText.of("plain text"))))
        assertThat(files.observeUsage(pdf.id).first()).isEmpty()
    }
}

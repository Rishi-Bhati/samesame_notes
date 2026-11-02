package org.samesame.notes.core.data.repository

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import java.io.File
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
import org.samesame.notes.core.document.NoteDocumentStore
import org.samesame.notes.core.document.NoteSnapshot
import org.samesame.notes.core.export.NoteExporter
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.box
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.document.NoteRecording
import org.samesame.notes.core.model.richtext.RichText

/** Deleting an untouched (or emptied) note when the editor closes it, against real Room and real bundles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmptyNoteDeletionTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val clock = Clock { now }

    private lateinit var db: SameSameDatabase
    private lateinit var documents: NoteDocumentStore
    private lateinit var notes: NoteRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SameSameDatabase::class.java).build()
        documents = NoteDocumentStore(tmp.newFolder("notes"), tmp.newFolder("working"))
        val settings = object : AppSettingsRepository {
            override val settings: Flow<AppSettings> = flowOf(AppSettings())
            override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
        }
        notes = DefaultNoteRepository(
            db.noteDao(), db.searchDao(), db.viewStateDao(), documents, clock, NoteExporter(), settings,
            PreviewFailureStore(tmp.newFolder("previews")),
            org.samesame.notes.core.database.DatabaseMaintenance(db),
            org.samesame.notes.core.data.transfer.ShareCache(tmp.newFolder("share")),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun hasBundle(id: String): Boolean =
        listOf("notes", "working").any { dir ->
            File(tmp.root, dir).listFiles().orEmpty().any { it.name.startsWith(id) }
        }

    private fun pageWithObject(id: NoteId, count: Int = 1) = runBlocking {
        val doc = documents.readDocument(id.value)
        val pageId = doc.pages.first().id
        val elements = List(count) { i -> ObjectElement(ElementId(i + 1L), "{}", box(0f, 0f, 10f, 10f)) }
        documents.commit(id.value, NoteSnapshot(doc, listOf(PageContent(pageId, elements, 1))))
        doc
    }

    @Test fun `a note just created is flagged as new exactly once`() = runBlocking {
        val id = notes.createNote(null)
        assertThat(notes.takeNewNote(id)).isTrue()
        assertThat(notes.takeNewNote(id)).isFalse()
        assertThat(notes.takeNewNote(NoteId("not-created-here"))).isFalse()
    }

    @Test fun `an empty note deleted before it is opened is no longer new`() = runBlocking {
        val id = notes.createNote(null)
        assertThat(notes.deleteIfEmpty(id)).isTrue()
        assertThat(notes.takeNewNote(id)).isFalse()
    }

    @Test fun `untouched new note is deleted with its catalog row and files`() = runBlocking {
        val id = notes.createNote(null)
        assertThat(hasBundle(id.value)).isTrue()

        assertThat(notes.deleteIfEmpty(id)).isTrue()

        assertThat(notes.observeNote(id).first()).isNull()
        assertThat(notes.observeNotes(org.samesame.notes.core.model.NoteFilter.All, org.samesame.notes.core.model.SortOrder()).first()).isEmpty()
        assertThat(hasBundle(id.value)).isFalse()
    }

    @Test fun `a queued save after the deletion cannot recreate the bundle`() = runBlocking {
        val id = notes.createNote(null)
        val doc = documents.readDocument(id.value)
        assertThat(notes.deleteIfEmpty(id)).isTrue()

        notes.saveContent(id, NoteSnapshot(doc))
        notes.flushToContainer(id, close = true)

        assertThat(notes.observeNote(id).first()).isNull()
        assertThat(hasBundle(id.value)).isFalse()
    }

    @Test fun `a title keeps the note`() = runBlocking {
        val id = notes.createNote(null)
        notes.updateTitle(id, "Ideas")
        assertThat(notes.deleteIfEmpty(id)).isFalse()
        assertThat(notes.observeNote(id).first()?.title).isEqualTo("Ideas")
    }

    @Test fun `body text keeps the note`() = runBlocking {
        val id = notes.createNote(null)
        val doc = documents.readDocument(id.value).copy(body = RichText.of("hello"))
        documents.writeDocument(doc)
        assertThat(notes.deleteIfEmpty(id)).isFalse()
    }

    @Test fun `ink or an object element on a page keeps the note`() = runBlocking {
        val id = notes.createNote(null)
        pageWithObject(id)
        assertThat(notes.deleteIfEmpty(id)).isFalse()
        assertThat(notes.observeNote(id).first()).isNotNull()
    }

    @Test fun `a voice recording keeps the note`() = runBlocking {
        val id = notes.createNote(null)
        val doc = documents.readDocument(id.value)
            .copy(recordings = listOf(NoteRecording(id = "r1", media = "media/a.m4a", elapsedMs = 1_000)))
        documents.writeDocument(doc)
        assertThat(notes.deleteIfEmpty(id)).isFalse()
    }

    @Test fun `an existing note emptied later is deleted`() = runBlocking {
        val id = notes.createNote(null)
        notes.updateTitle(id, "Draft")
        pageWithObject(id)
        assertThat(notes.deleteIfEmpty(id)).isFalse()

        // The user cleared the title and deleted every stroke/object (the body was never typed into).
        notes.updateTitle(id, "")
        val doc = documents.readDocument(id.value)
        documents.commit(id.value, NoteSnapshot(doc, listOf(PageContent(doc.pages.first().id, emptyList(), 2))))
        assertThat(notes.deleteIfEmpty(id)).isTrue()
        assertThat(notes.observeNote(id).first()).isNull()
        assertThat(hasBundle(id.value)).isFalse()
    }

    @Test fun `an unknown note is not reported as deleted`() = runBlocking {
        assertThat(notes.deleteIfEmpty(NoteId("missing"))).isFalse()
    }

    @Test fun `a moved-to-trash empty note is deleted too`() = runBlocking {
        val id = notes.createNote(null)
        notes.moveToTrash(listOf(id))
        assertThat(notes.deleteIfEmpty(id)).isTrue()
        assertThat(notes.observeNotes(org.samesame.notes.core.model.NoteFilter.Trash, org.samesame.notes.core.model.SortOrder()).first()).isEmpty()
    }
}

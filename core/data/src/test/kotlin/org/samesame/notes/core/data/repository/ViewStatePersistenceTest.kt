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
import org.samesame.notes.core.document.NoteDocumentStore
import org.samesame.notes.core.document.NoteSnapshot
import org.samesame.notes.core.export.NoteExporter
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.document.ViewState
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.security.LockCrypto
import org.samesame.notes.core.security.LockToken
import java.io.File

/**
 * rel011: looking at a note must not modify it. The reading position lives in its own per-note store, so
 * open -> scroll -> close keeps `modified_at` (home sort, note info, preview-cache key) and the `.ssnote` bytes
 * unchanged, and a locked note is not re-encrypted. A real edit still does all of that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewStatePersistenceTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val clock = Clock { now }

    private lateinit var db: SameSameDatabase
    private lateinit var documents: NoteDocumentStore
    private lateinit var notes: NoteRepository
    private lateinit var token: LockToken

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
        token = LockToken(LockCrypto.newKey())
    }

    @After
    fun tearDown() {
        token.close()
        db.close()
    }

    private fun containerBytes(id: NoteId): ByteArray = File(tmp.root, "notes/${id.value}.ssnote").readBytes()

    private suspend fun writtenNote(): NoteId {
        val id = notes.createNote(null)
        val doc = documents.readDocument(id.value).copy(title = "Plan", body = RichText.of("hello world"))
        notes.saveContent(id, NoteSnapshot(doc))
        notes.flushToContainer(id, close = true)
        return id
    }

    @Test fun `open scroll close leaves a plain note's modified time and bytes unchanged`() = runBlocking<Unit> {
        val id = writtenNote()
        val before = notes.observeNote(id).first()!!
        val beforeBytes = containerBytes(id)

        // What the composer does: open, restore (none yet), scroll (the debounced save), close (flush + pack).
        notes.openContent(id)
        assertThat(notes.loadViewState(id)).isNull()
        notes.saveViewState(id, ViewState(1, 240f, 1.5f))
        notes.flushToContainer(id, close = true)

        assertThat(notes.observeNote(id).first()!!.modifiedAt).isEqualTo(before.modifiedAt)
        assertThat(containerBytes(id)).isEqualTo(beforeBytes)
        // The position is still restored on the next open.
        assertThat(notes.loadViewState(id)).isEqualTo(ViewState(1, 240f, 1.5f))
    }

    @Test fun `an edit still bumps modified_at and rewrites the container`() = runBlocking<Unit> {
        val id = writtenNote()
        val before = notes.observeNote(id).first()
        val beforeBytes = containerBytes(id)

        notes.openContent(id)
        notes.saveViewState(id, ViewState(0, 10f, 1f))
        now += 60_000
        val doc = notes.openContent(id)
        notes.saveContent(id, NoteSnapshot(doc.copy(body = RichText.of("hello edited world"))))
        notes.flushToContainer(id, close = true)

        assertThat(notes.observeNote(id).first()!!.modifiedAt).isGreaterThan(before!!.modifiedAt)
        assertThat(containerBytes(id)).isNotEqualTo(beforeBytes)
    }

    @Test fun `open scroll close a locked note does not re-encrypt it`() = runBlocking<Unit> {
        val id = writtenNote()
        notes.lockNotes(listOf(id), token)
        val before = notes.observeNote(id).first()!!
        val beforeBytes = containerBytes(id)

        notes.openLockedNote(id, token)
        notes.openContent(id)
        notes.saveViewState(id, ViewState(0, 120f, 1f))
        notes.flushToContainer(id, close = true)

        assertThat(notes.observeNote(id).first()!!.modifiedAt).isEqualTo(before.modifiedAt)
        assertThat(containerBytes(id)).isEqualTo(beforeBytes)
        assertThat(notes.loadViewState(id)).isEqualTo(ViewState(0, 120f, 1f))
    }

    @Test fun `an edit of a locked note still re-encrypts it and bumps modified_at`() = runBlocking<Unit> {
        val id = writtenNote()
        notes.lockNotes(listOf(id), token)
        val before = notes.observeNote(id).first()
        val beforeBytes = containerBytes(id)

        notes.openLockedNote(id, token)
        val doc = notes.openContent(id)
        notes.saveViewState(id, ViewState(0, 120f, 1f))
        now += 60_000
        notes.saveContent(id, NoteSnapshot(doc.copy(title = "Plan edited")))
        notes.flushToContainer(id, close = true)

        assertThat(notes.observeNote(id).first()!!.modifiedAt).isGreaterThan(before!!.modifiedAt)
        assertThat(containerBytes(id)).isNotEqualTo(beforeBytes)
    }

    /** The old reader: a v6 document's `viewState` is moved into the store once, on the next open. */
    @Test fun `a v6 document's reading position is migrated into the store on open`() = runBlocking<Unit> {
        val id = writtenNote()
        val pageId = documents.readDocument(id.value).pages.first().id
        val document = File(tmp.root, "working/${id.value}/document.json")
        document.writeText(
            """{"formatVersion":6,"id":"${id.value}","title":"Plan","pages":[{"id":"$pageId"}],""" +
                """"viewState":{"pageIndex":0,"offsetInPage":88.0,"zoom":1.25}}""",
        )
        documents.flush(id.value, close = true) // pack the v6 document into the container
        assertThat(notes.loadViewState(id)).isNull()

        notes.openContent(id)

        assertThat(notes.loadViewState(id)).isEqualTo(ViewState(0, 88f, 1.25f))
    }
}

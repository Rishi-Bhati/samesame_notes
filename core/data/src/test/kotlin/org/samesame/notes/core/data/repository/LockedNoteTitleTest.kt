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
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.security.LockCrypto
import org.samesame.notes.core.security.LockToken

/**
 * Owner decision 2026-11-02: a locked note's title is visible (home page, lists, search) while its content is not.
 * The catalog keeps the title; only the preview text and the searchable body are dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockedNoteTitleTest {
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

    private suspend fun titledNote(title: String, body: String): NoteId {
        val id = notes.createNote(null)
        val doc = documents.readDocument(id.value).copy(title = title, body = RichText.of(body))
        notes.saveContent(id, NoteSnapshot(doc))
        return id
    }

    private suspend fun titles(query: String) = notes.search(query).first().map { it.title }

    @Test fun `locking keeps the title in the catalog and drops only the preview`() = runBlocking<Unit> {
        val id = titledNote("Pocket plan", "zebracorner body secret")

        notes.lockNotes(listOf(id), token)

        val row = notes.observeNote(id).first()!!
        assertThat(row.isLocked).isTrue()
        assertThat(row.title).isEqualTo("Pocket plan")
        assertThat(row.previewText).isEmpty()
    }

    @Test fun `a locked note is found by its title, never by its body`() = runBlocking<Unit> {
        val id = titledNote("Pocket plan", "zebracorner body secret")
        notes.lockNotes(listOf(id), token)

        assertThat(titles("Pocket")).containsExactly("Pocket plan")
        assertThat(notes.search("zebracorner").first()).isEmpty()
    }

    @Test fun `renaming a locked note updates the visible catalog title and its search entry`() = runBlocking<Unit> {
        val id = titledNote("Pocket plan", "zebracorner body secret")
        notes.lockNotes(listOf(id), token)

        notes.updateTitle(id, "Renamed while locked")

        assertThat(notes.observeNote(id).first()!!.title).isEqualTo("Renamed while locked")
        assertThat(titles("Renamed")).containsExactly("Renamed while locked")
        assertThat(notes.search("Pocket").first()).isEmpty()
        assertThat(notes.search("zebracorner").first()).isEmpty()
    }

    @Test fun `a save of a locked note keeps its title but not its body in the catalog`() = runBlocking<Unit> {
        val id = titledNote("Pocket plan", "zebracorner body secret")
        notes.lockNotes(listOf(id), token)
        notes.openLockedNote(id, token)

        val doc = documents.readDocument(id.value).copy(title = "Edited title", body = RichText.of("newbodytext"))
        notes.saveContent(id, NoteSnapshot(doc))

        val row = notes.observeNote(id).first()!!
        assertThat(row.title).isEqualTo("Edited title")
        assertThat(row.previewText).isEmpty()
        assertThat(titles("Edited")).containsExactly("Edited title")
        assertThat(notes.search("newbodytext").first()).isEmpty()
    }

    @Test fun `unlocking restores the body preview and body search`() = runBlocking<Unit> {
        val id = titledNote("Pocket plan", "zebracorner body secret")
        notes.lockNotes(listOf(id), token)
        notes.unlockNotes(listOf(id), token)

        val row = notes.observeNote(id).first()!!
        assertThat(row.isLocked).isFalse()
        assertThat(row.title).isEqualTo("Pocket plan")
        assertThat(row.previewText).contains("zebracorner")
        assertThat(notes.search("zebracorner").first().map { it.id }).containsExactly(id)
    }
}

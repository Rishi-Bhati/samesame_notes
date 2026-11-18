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
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.security.LockCrypto
import org.samesame.notes.core.security.LockToken

/** Checklist counts cached on the catalog row for home cards (rel031 D). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChecklistCountTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = Clock { 1_000_000L }
    private lateinit var db: SameSameDatabase
    private lateinit var documents: NoteDocumentStore
    private lateinit var notes: NoteRepository
    private lateinit var token: LockToken

    private val box = ParagraphStyle(checkbox = true)

    @Before fun setUp() {
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

    @After fun tearDown() {
        token.close()
        db.close()
    }

    private suspend fun noteWith(body: RichText): NoteId {
        val id = notes.createNote(null)
        notes.saveContent(id, NoteSnapshot(documents.readDocument(id.value).copy(body = body)))
        return id
    }

    private fun bodyWithChecks(text: String, checked: Set<Int>) = RichText.of(text).copy(
        paragraphs = text.split('\n').mapIndexed { i, _ -> box.copy(checked = i in checked) },
    )

    @Test fun `saving caches done and total from the body`() = runBlocking<Unit> {
        // Shopping: three items, two ticked.
        val id = noteWith(bodyWithChecks("Milk\nEggs\nBread", setOf(0, 2)))
        val row = notes.observeNote(id).first()!!
        assertThat(row.checklistDone).isEqualTo(2)
        assertThat(row.checklistTotal).isEqualTo(3)
    }

    @Test fun `a note without checkboxes has zero counts`() = runBlocking<Unit> {
        val id = noteWith(RichText.of("Just a plain note\nwith two lines"))
        val row = notes.observeNote(id).first()!!
        assertThat(row.checklistDone).isEqualTo(0)
        assertThat(row.checklistTotal).isEqualTo(0)
    }

    @Test fun `saving again refreshes the counts`() = runBlocking<Unit> {
        val id = noteWith(bodyWithChecks("Milk\nEggs", setOf(0)))
        notes.saveContent(id, NoteSnapshot(documents.readDocument(id.value).copy(body = bodyWithChecks("Milk\nEggs", setOf(0, 1)))))
        assertThat(notes.observeNote(id).first()!!.checklistDone).isEqualTo(2)
        assertThat(notes.observeNote(id).first()!!.checklistTotal).isEqualTo(2)
    }

    @Test fun `locking drops the counts and unlocking restores them`() = runBlocking<Unit> {
        val id = noteWith(bodyWithChecks("Milk\nEggs\nBread", setOf(0, 2)))
        notes.lockNotes(listOf(id), token)
        assertThat(notes.observeNote(id).first()!!.checklistTotal).isEqualTo(0)
        assertThat(notes.observeNote(id).first()!!.checklistDone).isEqualTo(0)

        // A save while locked keeps them at zero so no checklist content leaks into the catalog.
        notes.openLockedNote(id, token)
        notes.saveContent(id, NoteSnapshot(documents.readDocument(id.value).copy(body = bodyWithChecks("Milk\nEggs", setOf(0)))))
        assertThat(notes.observeNote(id).first()!!.checklistTotal).isEqualTo(0)

        notes.unlockNotes(listOf(id), token)
        assertThat(notes.observeNote(id).first()!!.checklistDone).isEqualTo(1)
        assertThat(notes.observeNote(id).first()!!.checklistTotal).isEqualTo(2)
    }
}

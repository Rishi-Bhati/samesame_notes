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
import org.samesame.notes.core.document.NoteVersionStore
import org.samesame.notes.core.export.NoteExporter
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.richtext.RichText
import java.io.File

/** Version history through the repository: restore re-indexes the catalog, the trash keeps versions, deleting forever removes them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteVersionsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000_000_000L
    private lateinit var db: SameSameDatabase
    private lateinit var versionsRoot: File
    private lateinit var documents: NoteDocumentStore
    private lateinit var notes: NoteRepository
    private lateinit var impl: DefaultNoteRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SameSameDatabase::class.java).build()
        versionsRoot = tmp.newFolder("versions")
        documents = NoteDocumentStore(tmp.newFolder("notes"), tmp.newFolder("working"), NoteVersionStore(versionsRoot, { now }))
        val settings = object : AppSettingsRepository {
            override val settings: Flow<AppSettings> = flowOf(AppSettings())
            override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
        }
        impl = DefaultNoteRepository(
            db.noteDao(), db.searchDao(), db.viewStateDao(), documents, Clock { now }, NoteExporter(), settings,
            PreviewFailureStore(tmp.newFolder("previews")),
            org.samesame.notes.core.database.DatabaseMaintenance(db),
            org.samesame.notes.core.data.transfer.ShareCache(tmp.newFolder("share")),
        )
        notes = impl
    }

    @After
    fun tearDown() = db.close()

    private suspend fun save(id: NoteId, title: String, body: String) {
        val doc = documents.readDocument(id.value).copy(title = title, body = RichText.of(body))
        notes.saveContent(id, NoteSnapshot(doc))
        notes.flushToContainer(id, close = false)
    }

    @Test fun `restore swaps the content and the catalog row and keeps the current state as a version`() = runBlocking<Unit> {
        val id = notes.createNote(null)
        save(id, "Old title", "old body")
        now += 10 * 60_000L
        save(id, "New title", "new body")
        val versions = notes.listVersions(id)
        assertThat(versions.map { it.title }).containsExactly("New title", "Old title").inOrder()

        notes.restoreVersion(id, versions.last().id)
        val row = notes.observeNote(id).first()!!
        assertThat(row.title).isEqualTo("Old title")
        assertThat(row.previewText).isEqualTo("old body")
        assertThat(notes.openContent(id).body.plainText()).isEqualTo("old body")
        // The state before the restore is already a version, so the restore can be undone.
        notes.restoreVersion(id, notes.listVersions(id).first { it.title == "New title" }.id)
        assertThat(notes.openContent(id).body.plainText()).isEqualTo("new body")
        assertThat(notes.observeNote(id).first()!!.title).isEqualTo("New title")
    }

    @Test fun `the trash keeps versions and deleting forever removes them`() = runBlocking<Unit> {
        val id = notes.createNote(null)
        save(id, "T", "body")
        assertThat(notes.listVersions(id)).isNotEmpty()
        notes.moveToTrash(listOf(id))
        assertThat(notes.listVersions(id)).isNotEmpty()
        notes.restoreFromTrash(listOf(id))
        assertThat(notes.listVersions(id)).isNotEmpty()
        notes.moveToTrash(listOf(id))
        notes.deleteForever(listOf(id))
        assertThat(notes.listVersions(id)).isEmpty()
        assertThat(File(versionsRoot, id.value).exists()).isFalse()
    }

    @Test fun `the 30 day trash purge removes versions too`() = runBlocking<Unit> {
        val id = notes.createNote(null)
        save(id, "T", "body")
        notes.moveToTrash(listOf(id))
        now += 31L * 24 * 60 * 60 * 1000
        notes.purgeExpiredTrash()
        assertThat(File(versionsRoot, id.value).exists()).isFalse()
    }
}

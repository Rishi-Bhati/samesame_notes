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
import org.samesame.notes.core.export.NoteExporter
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.NoteFilter
import org.samesame.notes.core.model.SortOrder

/** Folder trash/restore/delete-permanently/purge against real Room and real note bundles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val clock = Clock { now }

    private lateinit var db: SameSameDatabase
    private lateinit var notes: NoteRepository
    private lateinit var folders: FolderRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SameSameDatabase::class.java).build()
        val documents = NoteDocumentStore(tmp.newFolder("notes"), tmp.newFolder("working"))
        val settings = object : AppSettingsRepository {
            override val settings: Flow<AppSettings> = flowOf(AppSettings())
            override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
        }
        notes = DefaultNoteRepository(db.noteDao(), db.searchDao(), documents, clock, NoteExporter(), settings, PreviewFailureStore(tmp.newFolder("previews")), org.samesame.notes.core.database.DatabaseMaintenance(db), org.samesame.notes.core.data.transfer.ShareCache(tmp.newFolder("share")))
        folders = DefaultFolderRepository(db.folderDao(), notes, clock)
    }

    @After
    fun tearDown() = db.close()

    /** True while any file of the note's bundle exists on disk. */
    private fun hasBundle(id: String): Boolean =
        listOf("notes", "working").any { dir ->
            File(tmp.root, dir).listFiles().orEmpty().any { it.name.startsWith(id) }
        }

    private suspend fun trashedNotes(): List<String> =
        notes.observeNotes(NoteFilter.Trash, SortOrder()).first().map { it.id.value }

    @Test
    fun `trash stamps the folders and their notes with one timestamp`() = runBlocking<Unit> {
        val p = folders.createFolder("P", null)
        val a = folders.createFolder("A", p)
        val nP = notes.createNote(p)
        val nA = notes.createNote(a)
        val loose = notes.createNote(null)

        folders.trash(listOf(p))

        assertThat(folders.observeFolders().first()).isEmpty()
        val trashed = folders.observeTrashedFolders().first()
        assertThat(trashed.map { it.id }).containsExactly(p, a)
        assertThat(trashed.map { it.trashedAt }.toSet()).containsExactly(now)
        assertThat(trashedNotes()).containsExactly(nP.value, nA.value)
        assertThat(notes.observeNotes(NoteFilter.Trash, SortOrder()).first().map { it.trashedAt }.toSet()).containsExactly(now)
        assertThat(notes.observeNotes(NoteFilter.All, SortOrder()).first().map { it.id }).containsExactly(loose)
    }

    @Test
    fun `restore brings the whole subtree back and keeps the parent when it is live`() = runBlocking<Unit> {
        val p = folders.createFolder("P", null)
        val a = folders.createFolder("A", p)
        val nP = notes.createNote(p)
        folders.trash(listOf(p))

        folders.restore(listOf(p))

        val restored = folders.observeFolders().first()
        assertThat(restored.map { it.id }).containsExactly(p, a)
        assertThat(restored.first { it.id == a }.parentId).isEqualTo(p)
        assertThat(trashedNotes()).isEmpty()
        assertThat(notes.observeNotes(NoteFilter.All, SortOrder()).first().map { it.id }).containsExactly(nP)
    }

    @Test
    fun `restore goes to the top level when the original parent is trashed`() = runBlocking<Unit> {
        val p = folders.createFolder("P", null)
        val a = folders.createFolder("A", p)
        folders.trash(listOf(p))

        folders.restore(listOf(a))

        val restored = folders.observeFolders().first()
        assertThat(restored.map { it.id }).containsExactly(a)
        assertThat(restored.single().parentId).isNull()
        assertThat(folders.observeTrashedFolders().first().map { it.id }).containsExactly(p)
    }

    @Test
    fun `deletePermanently removes the rows and the notes bundle files`() = runBlocking<Unit> {
        val p = folders.createFolder("P", null)
        val a = folders.createFolder("A", p)
        val nP = notes.createNote(p)
        val nA = notes.createNote(a)
        val loose = notes.createNote(null)
        assertThat(hasBundle(nP.value)).isTrue()
        folders.trash(listOf(p))

        folders.deletePermanently(listOf(p))

        assertThat(folders.observeTrashedFolders().first()).isEmpty()
        assertThat(trashedNotes()).isEmpty()
        assertThat(notes.observeNotes(NoteFilter.All, SortOrder()).first().map { it.id }).containsExactly(loose)
        assertThat(hasBundle(nP.value)).isFalse()
        assertThat(hasBundle(nA.value)).isFalse()
        assertThat(hasBundle(loose.value)).isTrue()
    }

    @Test
    fun `purge removes folders trashed longer than the retention, bundles included`() = runBlocking<Unit> {
        val old = folders.createFolder("Old", null)
        val nOld = notes.createNote(old)
        folders.trash(listOf(old))
        now += 31L * 24 * 60 * 60 * 1000
        val fresh = folders.createFolder("Fresh", null)
        val nFresh = notes.createNote(fresh)
        folders.trash(listOf(fresh))

        folders.purgeExpiredTrash()

        assertThat(folders.observeTrashedFolders().first().map { it.id }).containsExactly(fresh)
        assertThat(trashedNotes()).containsExactly(nFresh.value)
        assertThat(hasBundle(nOld.value)).isFalse()
        assertThat(hasBundle(nFresh.value)).isTrue()
    }

    @Test
    fun `deletePermanently of a subfolder alone leaves its parent alive`() = runBlocking<Unit> {
        val p = folders.createFolder("P", null)
        val a = folders.createFolder("A", p)
        folders.trash(listOf(a))

        folders.deletePermanently(listOf(a))

        assertThat(folders.observeTrashedFolders().first()).isEmpty()
        assertThat(folders.observeFolders().first().map { it.id }).containsExactly(p)
    }
}

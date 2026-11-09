package org.samesame.notes.core.data.repository

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
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
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.security.LockCrypto
import org.samesame.notes.core.security.LockToken
import java.util.concurrent.TimeUnit

/**
 * L1 (0.1.2): locked notes are never deleted (Recycle bin or permanently, directly or inside a folder) without a verified
 * [LockToken]. Only the internal 30-day purge works without one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockedNoteDeleteTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val clock = Clock { now }

    private lateinit var db: SameSameDatabase
    private lateinit var documents: NoteDocumentStore
    private lateinit var notes: NoteRepository
    private lateinit var folders: FolderRepository
    private lateinit var master: ByteArray

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
        folders = DefaultFolderRepository(db.folderDao(), notes, clock)
        master = LockCrypto.newKey()
    }

    @After
    fun tearDown() = db.close()

    private fun token() = LockToken(master)

    private suspend fun note(title: String, folder: FolderId? = null): NoteId {
        val id = notes.createNote(folder)
        val doc = documents.readDocument(id.value).copy(title = title, body = RichText.of("secret body"))
        notes.saveContent(id, NoteSnapshot(doc))
        notes.flushToContainer(id, close = true)
        return id
    }

    private suspend fun lockedNote(title: String, folder: FolderId? = null): NoteId =
        note(title, folder).also { id -> token().use { notes.lockNotes(listOf(id), it) } }

    private suspend fun row(id: NoteId) = notes.observeNote(id).first()

    @Test fun `trashing a locked note without a token is refused and moves nothing`() = runBlocking<Unit> {
        val plain = note("Plain")
        val locked = lockedNote("Locked")

        val e = assertThrows(LockVerificationRequiredException::class.java) {
            runBlocking { notes.moveToTrash(listOf(plain, locked)) }
        }

        assertThat(e.noteIds).containsExactly(locked)
        assertThat(row(plain)!!.trashedAt).isNull()
        assertThat(row(locked)!!.trashedAt).isNull()
    }

    @Test fun `a verified token trashes and deletes locked notes`() = runBlocking<Unit> {
        val locked = lockedNote("Locked")

        token().use { notes.moveToTrash(listOf(locked), it) }
        assertThat(row(locked)!!.trashedAt).isNotNull()

        token().use { notes.deleteForever(listOf(locked), it) }
        assertThat(row(locked)).isNull()
    }

    @Test fun `a foreign or closed token is refused`() = runBlocking<Unit> {
        val locked = lockedNote("Locked")

        LockToken(LockCrypto.newKey()).use { foreign ->
            assertThrows(LockVerificationRequiredException::class.java) {
                runBlocking { notes.deleteForever(listOf(locked), foreign) }
            }
        }
        val closed = token().also { it.close() }
        assertThrows(LockVerificationRequiredException::class.java) {
            runBlocking { notes.moveToTrash(listOf(locked), closed) }
        }
        assertThat(row(locked)).isNotNull()
        assertThat(row(locked)!!.trashedAt).isNull()
    }

    @Test fun `delete forever from the Recycle bin needs a token`() = runBlocking<Unit> {
        val locked = lockedNote("Locked")
        token().use { notes.moveToTrash(listOf(locked), it) }

        assertThrows(LockVerificationRequiredException::class.java) {
            runBlocking { notes.deleteForever(listOf(locked)) }
        }
        assertThat(row(locked)).isNotNull()
    }

    @Test fun `unlocked notes need no token`() = runBlocking<Unit> {
        val plain = note("Plain")
        notes.moveToTrash(listOf(plain))
        notes.deleteForever(listOf(plain))
        assertThat(row(plain)).isNull()
    }

    @Test fun `a locked note open in the editor counts as verified`() = runBlocking<Unit> {
        val locked = lockedNote("Locked")
        token().use { notes.openLockedNote(locked, it) }

        notes.moveToTrash(listOf(locked))

        assertThat(row(locked)!!.trashedAt).isNotNull()
    }

    @Test fun `a folder holding a locked note is trashed and deleted only with a token`() = runBlocking<Unit> {
        val parent = folders.createFolder("P", null)
        val child = folders.createFolder("C", parent)
        val locked = lockedNote("Deep", child)

        assertThat(folders.lockedNoteIdsIn(listOf(parent))).containsExactly(locked)
        assertThrows(LockVerificationRequiredException::class.java) {
            runBlocking { folders.trash(listOf(parent)) }
        }
        assertThat(folders.observeTrashedFolders().first()).isEmpty()
        assertThat(row(locked)!!.trashedAt).isNull()

        token().use { folders.trash(listOf(parent), it) }
        assertThat(row(locked)!!.trashedAt).isNotNull()

        assertThrows(LockVerificationRequiredException::class.java) {
            runBlocking { folders.deletePermanently(listOf(parent)) }
        }
        assertThat(row(locked)).isNotNull()
        assertThat(folders.observeTrashedFolders().first()).isNotEmpty()

        token().use { folders.deletePermanently(listOf(parent), it) }
        assertThat(row(locked)).isNull()
        assertThat(folders.observeTrashedFolders().first()).isEmpty()
    }

    @Test fun `the 30-day purge needs no token`() = runBlocking<Unit> {
        val locked = lockedNote("Old")
        val folder = folders.createFolder("Old folder", null)
        val inFolder = lockedNote("Old in folder", folder)
        token().use {
            notes.moveToTrash(listOf(locked), it)
            folders.trash(listOf(folder), it)
        }
        now += TimeUnit.DAYS.toMillis(Limits.TRASH_RETENTION_DAYS.toLong() + 1)

        folders.purgeExpiredTrash()
        notes.purgeExpiredTrash()

        assertThat(row(locked)).isNull()
        assertThat(row(inFolder)).isNull()
    }

    @Test fun `lockedNoteIds lists only the locked ones`() = runBlocking<Unit> {
        val plain = note("Plain")
        val locked = lockedNote("Locked")
        assertThat(notes.lockedNoteIds(listOf(plain, locked))).containsExactly(locked)
    }
}

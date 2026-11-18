package org.samesame.notes.core.data.repository

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
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
import org.samesame.notes.core.model.NoteKind
import org.samesame.notes.core.security.LockCrypto
import org.samesame.notes.core.security.LockToken

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownNoteRepositoryTest {
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

    @Test
    fun markdownNoteReadWriteRoundTrip() = runBlocking {
        val id = notes.createMarkdownNote(null)
        val note = notes.getNote(id)
        assertThat(note).isNotNull()
        assertThat(note!!.kind).isEqualTo(NoteKind.Markdown)

        // Initially empty
        val initialText = notes.readMarkdown(id)
        assertThat(initialText).isEqualTo("")

        // Write markdown content
        val markdownText = "# Title\n\n" + "a".repeat(400)
        now = 2_000_000L
        notes.writeMarkdown(id, markdownText)

        // Read back
        val readBack = notes.readMarkdown(id)
        assertThat(readBack).isEqualTo(markdownText)

        // Verify catalog preview is first 300 chars and modifiedAt is updated
        val updatedNote = notes.getNote(id)
        assertThat(updatedNote).isNotNull()
        assertThat(updatedNote!!.previewText).isEqualTo(markdownText.take(300))
        assertThat(updatedNote.previewText.length).isEqualTo(300)
        assertThat(updatedNote.modifiedAt).isEqualTo(2_000_000L)
    }

    @Test
    fun lockedMarkdownNoteReturnsNullOnRead() = runBlocking {
        val id = notes.createMarkdownNote(null)
        notes.writeMarkdown(id, "# Secret")

        notes.lockNotes(listOf(id), token)
        val lockedNote = notes.getNote(id)
        assertThat(lockedNote?.isLocked).isTrue()

        // Read markdown on locked note returns null
        assertThat(notes.readMarkdown(id)).isNull()
    }
}

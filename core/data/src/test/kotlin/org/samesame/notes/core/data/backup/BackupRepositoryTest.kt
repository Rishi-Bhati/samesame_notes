package org.samesame.notes.core.data.backup

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import org.samesame.notes.core.data.repository.AppSettingsRepository
import org.samesame.notes.core.data.repository.DefaultNoteRepository
import org.samesame.notes.core.data.repository.PreviewFailureStore
import org.samesame.notes.core.data.transfer.ShareCache
import org.samesame.notes.core.database.DatabaseMaintenance
import org.samesame.notes.core.database.SameSameDatabase
import org.samesame.notes.core.database.entity.FileFolderEntity
import org.samesame.notes.core.database.entity.FolderEntity
import org.samesame.notes.core.database.entity.LibraryFileEntity
import org.samesame.notes.core.database.entity.NoteEntity
import org.samesame.notes.core.database.entity.NoteSearchEntity
import org.samesame.notes.core.document.NoteDocumentStore
import org.samesame.notes.core.export.NoteExporter
import org.samesame.notes.core.files.BlobStore
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.security.LockCrypto
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_700_000_000_000L
    private val clock = Clock { now }
    private lateinit var db: SameSameDatabase
    private lateinit var documents: NoteDocumentStore
    private lateinit var blobStore: BlobStore
    private lateinit var settingsRepo: FakeSettingsRepo
    private lateinit var repo: BackupRepository
    private lateinit var backupRoot: File
    private lateinit var folder: JavaIoBackupFolder

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, SameSameDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        documents = NoteDocumentStore(tmp.newFolder("notes"), tmp.newFolder("working"))
        blobStore = BlobStore(tmp.newFolder("library"), freeSpace = { Long.MAX_VALUE / 2 })
        settingsRepo = FakeSettingsRepo()
        val noteRepo = DefaultNoteRepository(
            db.noteDao(),
            db.searchDao(),
            db.viewStateDao(),
            documents,
            clock,
            NoteExporter(),
            settingsRepo,
            PreviewFailureStore(tmp.newFolder("previews")),
            DatabaseMaintenance(db),
            ShareCache(tmp.newFolder("share")),
        )
        repo = BackupRepository(
            app,
            db.noteDao(),
            db.folderDao(),
            db.libraryDao(),
            db.searchDao(),
            documents,
            blobStore,
            settingsRepo,
            clock,
            noteRepo,
        )
        backupRoot = tmp.newFolder("backup")
        folder = JavaIoBackupFolder(backupRoot)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `manifest round trip`() {
        val manifest = BackupManifest(
            formatVersion = BACKUP_FORMAT_VERSION,
            appVersion = "1.2.3",
            createdTime = 123456789L,
            notes = listOf(
                BackupNoteEntry(
                    id = "n1",
                    title = "",
                    modifiedTime = 100L,
                    folderPath = listOf("A", "B"),
                    favourite = true,
                    locked = true,
                    fileName = "n1.ssnote",
                    size = 42L,
                    sha256 = "abc",
                ),
                BackupNoteEntry(
                    id = "n2",
                    title = "Hello",
                    modifiedTime = 200L,
                    folderPath = emptyList(),
                    favourite = false,
                    locked = false,
                    fileName = "n2.ssnote",
                    size = 99L,
                    sha256 = "def",
                ),
            ),
            libraryFiles = listOf(
                BackupLibraryFileEntry(
                    id = "f1",
                    folderPath = listOf("Docs"),
                    displayName = "x.txt",
                    ext = "txt",
                    mimeType = "text/plain",
                    category = "Document",
                    sizeBytes = 5,
                    sha256 = "sha1",
                    width = null,
                    height = null,
                    durationMs = null,
                    pageCount = null,
                    sourceApp = null,
                    createdAt = 1L,
                    modifiedAt = 2L,
                    sortIndex = 3L,
                    isFavorite = false,
                    trashedAt = null,
                    description = "d",
                    tags = listOf("a", "b"),
                    blobFileName = "sha1",
                ),
            ),
        )
        val json = manifest.toJson()
        val back = BackupManifest.fromJson(json)
        assertThat(back).isEqualTo(manifest)
    }

    @Test
    fun `prune keeps newest complete backups and ignores partial and foreign folders`() = runBlocking<Unit> {
        fun mk(name: String, created: Long, complete: Boolean = true, manifest: Boolean = true, foreign: Boolean = false) {
            val dir = File(backupRoot, name)
            dir.mkdirs()
            if (manifest) {
                val m = BackupManifest(appVersion = "1", createdTime = created, notes = emptyList(), libraryFiles = emptyList())
                File(dir, BACKUP_MANIFEST_FILE).writeText(m.toJson())
            }
            if (!complete || foreign) {
                // for foreign marker use different naming handled by caller
            }
        }
        // complete backups (newest first by createdTime)
        mk("SameSame backup 2026-01-01 0100", 100)
        mk("SameSame backup 2026-01-02 0100", 200)
        mk("SameSame backup 2026-01-03 0100", 300)
        mk("SameSame backup 2026-01-04 0100", 400)
        // partial (should be removed)
        File(backupRoot, "SameSame backup 2026-01-05 0100.partial").mkdirs()
        // foreign/other folders
        File(backupRoot, "Other backup 2026-01-06 0100").mkdirs()
        File(backupRoot, "SameSame backup 2026-01-07 0100").mkdirs() // complete-name but missing manifest -> not a valid complete backup to count/keep by our logic? prune counts those with manifest

        repo.pruneBackups(folder, keep = 2)

        assertThat(folder.exists("SameSame backup 2026-01-04 0100")).isTrue()
        assertThat(folder.exists("SameSame backup 2026-01-03 0100")).isTrue()
        assertThat(folder.exists("SameSame backup 2026-01-02 0100")).isFalse()
        assertThat(folder.exists("SameSame backup 2026-01-01 0100")).isFalse()
        assertThat(folder.exists("SameSame backup 2026-01-05 0100.partial")).isFalse()
        assertThat(File(backupRoot, "Other backup 2026-01-06 0100").exists()).isTrue()
        assertThat(File(backupRoot, "SameSame backup 2026-01-07 0100").exists()).isTrue()
    }

    @Test
    fun `backup and restore creates notes when absent`() = runBlocking<Unit> {
        val rootId = insertFolder(null, "Root", 1)
        val childId = insertFolder(rootId, "Child", 2)
        val n1 = createNote("Hello", "body", folderId = childId, modified = 1000L)
        val n2 = createNote("Two", "two", folderId = null, modified = 2000L, favorite = true)

        val result = repo.runBackup(folder)
        assertThat(result).isInstanceOf(BackupRunResult.Success::class.java)
        val success = result as BackupRunResult.Success
        assertThat(success.noteCount).isEqualTo(2)

        // wipe app data (db + note files + working)
        db.noteDao().delete(listOf(n1, n2))
        db.searchDao().deleteForNote(n1)
        db.searchDao().deleteForNote(n2)
        db.folderDao().allFolders().map { it.id }.let { if (it.isNotEmpty()) db.folderDao().delete(it) }
        documents.delete(n1)
        documents.delete(n2)

        val restore = repo.restoreFromFolder(folder, success.folderName)
        assertThat(restore.restoredNotes).isEqualTo(2)
        assertThat(restore.skippedNotes).isEqualTo(0)
        assertThat(restore.problems).isEmpty()

        val r1 = db.noteDao().get(n1)
        val r2 = db.noteDao().get(n2)
        assertThat(r1).isNotNull()
        assertThat(r2).isNotNull()
        assertThat(r1!!.title).isEqualTo("Hello")
        assertThat(r2!!.isFavorite).isTrue()
        // folder path recreated and assigned
        assertThat(r1.folderId).isNotNull()
        val restoredFolder = db.folderDao().get(r1.folderId!!)
        assertThat(restoredFolder?.name).isEqualTo("Child")
        assertThat(db.folderDao().get(restoredFolder!!.parentId!!)?.name).isEqualTo("Root")
        // content restored
        assertThat(documents.readDocument(n1).body.plainText()).isEqualTo("body")
    }

    @Test
    fun `restore skips same or older backup without overwriting local note`() = runBlocking<Unit> {
        val id = createNote("Original", "v1", modified = 1000L)
        val backup1 = (repo.runBackup(folder) as BackupRunResult.Success).folderName

        // make local newer than backup -> backup restore should skip
        val localDoc = documents.readDocument(id).copy(title = "LocalNew", body = RichText.of("local"))
        documents.writeDocument(localDoc)
        documents.flush(id, close = true)
        db.noteDao().upsert(db.noteDao().get(id)!!.copy(title = "LocalNew", previewText = "local", modifiedAt = 2000L, isDirty = true))
        val rSkip = repo.restoreFromFolder(folder, backup1)
        assertThat(rSkip.restoredNotes).isEqualTo(0)
        assertThat(rSkip.skippedNotes).isEqualTo(1)
        assertThat(db.noteDao().get(id)!!.title).isEqualTo("LocalNew")
        assertThat(documents.readDocument(id).body.plainText()).isEqualTo("local")
        assertThat(db.noteDao().getAll().size).isEqualTo(1)
    }

    @Test
    fun `restore copies newer backup of existing unlocked note as restored copy`() = runBlocking<Unit> {
        val id = createNote("Base", "old", modified = 1000L)
        // backup with newer modified
        db.noteDao().upsert(db.noteDao().get(id)!!.copy(modifiedAt = 3000L))
        val doc = documents.readDocument(id).copy(title = "FromBackup", body = RichText.of("backup-body"))
        documents.writeDocument(doc)
        documents.flush(id, close = true)
        val backup = (repo.runBackup(folder) as BackupRunResult.Success).folderName

        // local becomes older/different
        val localDoc = documents.readDocument(id).copy(title = "Local", body = RichText.of("local-body"))
        documents.writeDocument(localDoc)
        documents.flush(id, close = true)
        db.noteDao().upsert(db.noteDao().get(id)!!.copy(title = "Local", previewText = "local-body", modifiedAt = 2000L))

        val before = db.noteDao().getAll().map { it.id }.toSet()
        val result = repo.restoreFromFolder(folder, backup)
        assertThat(result.restoredNotes).isEqualTo(1)
        assertThat(result.skippedNotes).isEqualTo(0)
        // original untouched
        assertThat(db.noteDao().get(id)!!.title).isEqualTo("Local")
        assertThat(documents.readDocument(id).body.plainText()).isEqualTo("local-body")
        // copy added
        val all = db.noteDao().getAll()
        assertThat(all.size).isEqualTo(2)
        val copy = all.first { it.id !in before }
        assertThat(copy.title).isEqualTo("FromBackup (restored)")
        assertThat(documents.readDocument(copy.id).title).isEqualTo("FromBackup (restored)")
        assertThat(documents.readDocument(copy.id).body.plainText()).isEqualTo("backup-body")
    }

    @Test
    fun `restore skips locked note when backup newer and never overwrites`() = runBlocking<Unit> {
        val id = createLockedNote("Secret", "locked-body", modified = 1000L)
        val backup = (repo.runBackup(folder) as BackupRunResult.Success).folderName

        // make local "older" in catalog but keep locked container same id; backup newer -> should skip (cannot copy locked)
        // simulate by setting local modified older
        db.noteDao().upsert(db.noteDao().get(id)!!.copy(modifiedAt = 500L))
        val result = repo.restoreFromFolder(folder, backup)
        assertThat(result.skippedNotes).isEqualTo(1)
        assertThat(result.restoredNotes).isEqualTo(0)
        assertThat(result.problems).isNotEmpty()
        // original still locked and only one note
        assertThat(db.noteDao().getAll().size).isEqualTo(1)
        assertThat(db.noteDao().get(id)!!.isLocked).isTrue()
        assertThat(documents.isLocked(id)).isTrue()
    }

    @Test
    fun `restore installs locked note when absent and keeps it locked`() = runBlocking<Unit> {
        val id = createLockedNote("Secret", "locked-body", modified = 1000L)
        val backup = (repo.runBackup(folder) as BackupRunResult.Success).folderName

        // remove local
        db.noteDao().delete(listOf(id))
        db.searchDao().deleteForNote(id)
        documents.delete(id)

        val result = repo.restoreFromFolder(folder, backup)
        assertThat(result.restoredNotes).isEqualTo(1)
        assertThat(result.skippedNotes).isEqualTo(0)
        val row = db.noteDao().get(id)
        assertThat(row).isNotNull()
        assertThat(row!!.isLocked).isTrue()
        assertThat(row.title).isEmpty() // locked notes have empty visible/catalog title in backup manifest; catalog restored accordingly
        assertThat(documents.isLocked(id)).isTrue()
    }

    @Test
    fun `restore skips file with sha mismatch and reports problem`() = runBlocking<Unit> {
        val id = createNote("A", "body", modified = 1000L)
        val backup = (repo.runBackup(folder) as BackupRunResult.Success).folderName
        // corrupt the backed up note file
        val notePath = File(File(backupRoot, backup), "$BACKUP_NOTES_DIR/$id.ssnote")
        notePath.writeBytes(byteArrayOf(1, 2, 3, 4))

        db.noteDao().delete(listOf(id))
        db.searchDao().deleteForNote(id)
        documents.delete(id)

        val result = repo.restoreFromFolder(folder, backup)
        assertThat(result.restoredNotes).isEqualTo(0)
        assertThat(result.skippedNotes).isEqualTo(1)
        assertThat(result.problems.joinToString()).contains("SHA mismatch")
        assertThat(db.noteDao().get(id)).isNull()
    }

    @Test
    fun `backup and restore library blobs and folders`() = runBlocking<Unit> {
        val libFolderId = UUID.randomUUID().toString()
        val now = clock.now()
        db.libraryDao().insertFolder(FileFolderEntity(libFolderId, "Pics", null, null, 0L, now, now, null))
        val sha = writeBlob("hello-lib")
        val fileId = UUID.randomUUID().toString()
        db.libraryDao().insertFile(
            LibraryFileEntity(
                id = fileId,
                folderId = libFolderId,
                displayName = "pic",
                ext = "txt",
                mimeType = "text/plain",
                category = FileCategory.Document,
                sizeBytes = 9L,
                sha256 = sha,
                width = null,
                height = null,
                durationMs = null,
                pageCount = null,
                sourceApp = null,
                createdAt = now,
                modifiedAt = now,
                sortIndex = 1L,
                isFavorite = false,
                trashedAt = null,
                description = "",
            ),
        )
        db.libraryDao().replaceTags(fileId, listOf("tag1"))

        val backup = (repo.runBackup(folder) as BackupRunResult.Success).folderName

        // wipe library
        db.libraryDao().deleteTags(fileId)
        db.libraryDao().deleteFiles(listOf(fileId))
        db.libraryDao().deleteFolders(listOf(libFolderId))
        blobStore.delete(sha)

        val result = repo.restoreFromFolder(folder, backup)
        assertThat(result.restoredLibraryFiles).isEqualTo(1)
        val restored = db.libraryDao().file(fileId)
        assertThat(restored).isNotNull()
        assertThat(restored!!.sha256).isEqualTo(sha)
        assertThat(blobStore.file(sha).readText()).isEqualTo("hello-lib")
        val tags = db.libraryDao().observeAllTags().first().filter { it.fileId == fileId }.map { it.tag }
        assertThat(tags).containsExactly("tag1")
        val restoredFolder = db.libraryDao().folder(restored.folderId!!)
        assertThat(restoredFolder?.name).isEqualTo("Pics")

        // restore again -> identical sha skipped
        val again = repo.restoreFromFolder(folder, backup)
        assertThat(again.restoredLibraryFiles).isEqualTo(0)
    }

    @Test
    fun `backup folder name format and parse round trip and backward compatibility`() {
        val timeMs = 1_700_000_000_000L

        val oldSdf = java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }
        val oldFolderName = "SameSame backup " + oldSdf.format(java.util.Date(timeMs))
        val expectedParsedTimeMs = oldSdf.parse(oldSdf.format(java.util.Date(timeMs)))!!.time

        val parsedFromOld = parseBackupFolderTime(oldFolderName)
        assertThat(parsedFromOld).isEqualTo(expectedParsedTimeMs)

        val newFolderName = backupFolderName(timeMs)
        assertThat(newFolderName).isEqualTo(oldFolderName)

        val parsedFromNew = parseBackupFolderTime(newFolderName)
        assertThat(parsedFromNew).isEqualTo(expectedParsedTimeMs)

        val partialName = "$newFolderName.partial"
        assertThat(parseBackupFolderTime(partialName)).isEqualTo(expectedParsedTimeMs)
    }

    @Test
    fun `pre-existing truncated blob is replaced on restore`() = runBlocking<Unit> {
        val now = clock.now()
        val content = "full-length-blob-content-12345"
        val sha = writeBlob(content)
        val fileId = UUID.randomUUID().toString()
        db.libraryDao().insertFile(
            LibraryFileEntity(
                id = fileId,
                folderId = null,
                displayName = "doc",
                ext = "txt",
                mimeType = "text/plain",
                category = FileCategory.Document,
                sizeBytes = content.length.toLong(),
                sha256 = sha,
                width = null,
                height = null,
                durationMs = null,
                pageCount = null,
                sourceApp = null,
                createdAt = now,
                modifiedAt = now,
                sortIndex = 1L,
                isFavorite = false,
                trashedAt = null,
                description = "",
            ),
        )

        val backup = (repo.runBackup(folder) as BackupRunResult.Success).folderName

        // Simulate interrupted prior restore: destination exists with non-zero truncated content
        val destFile = blobStore.file(sha)
        destFile.writeBytes("truncated-prefix".toByteArray())
        assertThat(destFile.length()).isGreaterThan(0L)
        assertThat(destFile.length()).isLessThan(content.length.toLong())

        // Clear file record from DB so restore attempts to restore it
        db.libraryDao().deleteFiles(listOf(fileId))

        val result = repo.restoreFromFolder(folder, backup)
        assertThat(result.restoredLibraryFiles).isEqualTo(1)

        // Truncated blob must have been replaced with full content
        assertThat(blobStore.file(sha).readText()).isEqualTo(content)
    }

    private fun createNote(
        title: String,
        body: String,
        folderId: String? = null,
        modified: Long,
        favorite: Boolean = false,
        locked: Boolean = false,
    ): String {
        val id = UUID.randomUUID().toString()
        val doc = NoteDocument(id = id, title = title, pages = listOf(PageSpec("p1")), body = RichText.of(body))
        runBlocking { documents.createFromDocument(doc) }
        runBlocking {
            db.noteDao().upsert(
                NoteEntity(
                    id = id,
                    title = title,
                    previewText = body.take(300),
                    folderId = folderId,
                    isFavorite = favorite,
                    isLocked = locked,
                    pageMode = PageMode.Pages,
                    backgroundColor = 0,
                    createdAt = modified,
                    modifiedAt = modified,
                    lastOpenedAt = modified,
                    trashedAt = null,
                    isDirty = true,
                ),
            )
            db.searchDao().replace(NoteSearchEntity(noteId = id, title = title, body = body, inkText = ""))
        }
        return id
    }

    private fun createLockedNote(title: String, body: String, modified: Long): String {
        val id = createNote(title, body, modified = modified)
        val master = LockCrypto.newKey()
        try {
            runBlocking { documents.lockNote(id, master, title) }
        } finally {
            master.fill(0)
        }
        runBlocking {
            db.noteDao().upsert(db.noteDao().get(id)!!.copy(title = title, previewText = "", isLocked = true, modifiedAt = modified))
            db.searchDao().replace(NoteSearchEntity(noteId = id, title = "", body = "", inkText = ""))
        }
        return id
    }

    private fun insertFolder(parentId: String?, name: String, sortOrder: Int): String = runBlocking {
        val id = UUID.randomUUID().toString()
        val t = clock.now()
        db.folderDao().insert(FolderEntity(id, parentId, name, null, sortOrder, t, t, null))
        id
    }

    private fun writeBlob(content: String): String {
        val bytes = content.toByteArray()
        val sha = bytes.sha256Hex()
        val file = blobStore.file(sha)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return sha
    }

    private class FakeSettingsRepo(initial: AppSettings = AppSettings()) : AppSettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state
        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            state.value = transform(state.value)
        }
    }
}

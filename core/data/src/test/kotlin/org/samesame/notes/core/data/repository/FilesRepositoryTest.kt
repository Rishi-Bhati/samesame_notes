package org.samesame.notes.core.data.repository

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
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
import org.samesame.notes.core.database.entity.NoteEntity
import org.samesame.notes.core.files.BlobStore
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.files.DuplicatePolicy
import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.FileRefKind
import org.samesame.notes.core.model.files.FileSort
import org.samesame.notes.core.model.files.FileSortField
import org.samesame.notes.core.model.files.ImportResult
import org.samesame.notes.core.model.files.LibraryFile
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** FilesRepository against real Room (Robolectric SQLite) and a real blob store in a temp folder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilesRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private lateinit var db: SameSameDatabase
    private lateinit var blobs: BlobStore
    private lateinit var repo: DefaultFilesRepository

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(100) { it.toByte() }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SameSameDatabase::class.java).build()
        blobs = BlobStore(tmp.newFolder("library"), freeSpace = { Long.MAX_VALUE / 2 })
        repo = DefaultFilesRepository(RuntimeEnvironment.getApplication(), db.libraryDao(), blobs, Clock { now })
    }

    @After fun tearDown() = db.close()

    private suspend fun import(
        bytes: ByteArray,
        name: String,
        folder: org.samesame.notes.core.model.files.FileFolderId? = null,
        policy: DuplicatePolicy = DuplicatePolicy.Ask,
    ): ImportResult = repo.importStream({ ByteArrayInputStream(bytes) }, name, folder, onDuplicate = policy)

    private fun ImportResult.file(): LibraryFile = (this as ImportResult.Imported).file

    @Test fun `import stores a sanitised, sniffed copy`() = runBlocking<Unit> {
        val f = import(png, "  holiday/photo‮ ").file()
        assertThat(f.displayName).isEqualTo("holiday_photo.png") // no extension: one is added from the content
        assertThat(f.mimeType).isEqualTo("image/png")
        assertThat(f.category).isEqualTo(FileCategory.Image)
        assertThat(f.isRenderableImage).isTrue()
        assertThat(f.sizeBytes).isEqualTo(png.size.toLong())
        assertThat(f.id.isWellFormed).isTrue()
        assertThat(blobs.file(f.sha256).readBytes()).isEqualTo(png)
        assertThat(repo.observeSummary().first().fileCount).isEqualTo(1)
    }

    @Test fun `dedupe - same folder asks, keep both and skip, other folders share the blob silently`() = runBlocking<Unit> {
        val first = import(png, "a.png").file()
        val dup = import(png, "again.png") as ImportResult.Duplicate
        assertThat(dup.existing.id).isEqualTo(first.id)
        assertThat(repo.observeFiles(null, FileSort()).first()).hasSize(1)

        val kept = repo.resolveDuplicate(dup.pending, keepBoth = true)!!
        assertThat(kept.displayName).isEqualTo("again.png")
        assertThat(repo.resolveDuplicate(dup.pending, keepBoth = false)).isNull()
        assertThat(import(png, "a.png", policy = DuplicatePolicy.Skip)).isInstanceOf(ImportResult.Skipped::class.java)
        val both = import(png, "a.png", policy = DuplicatePolicy.KeepBoth).file()
        assertThat(both.displayName).isEqualTo("a (2).png")

        val folder = repo.createFolder("Trips", null)
        val elsewhere = import(png, "a.png", folder)
        assertThat(elsewhere).isInstanceOf(ImportResult.Imported::class.java)
        assertThat(blobs.list()).containsExactly(first.sha256) // one blob for four entries
        assertThat(repo.observeAllFiles(FileSort()).first()).hasSize(4)
    }

    @Test fun `atomic import - a source failing mid-copy leaves no row, temp file or blob`() = runBlocking<Unit> {
        val dying = object : InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 500_000) 1 else throw IOException("killed")
        }
        assertThrows(IOException::class.java) { runBlocking { repo.importStream({ dying }, "big.bin", null) } }
        assertThat(repo.observeAllFiles(FileSort()).first()).isEmpty()
        assertThat(blobs.tmpDir.list().orEmpty()).isEmpty()
        assertThat(blobs.list()).isEmpty()
    }

    @Test fun `atomic import - leftovers of a killed process are removed at start, referenced content stays`() = runBlocking<Unit> {
        val kept = import(png, "kept.png").file()
        // Killed mid-copy: a temp file. Killed between the rename and the row: an unreferenced blob.
        blobs.tmpDir.mkdirs()
        java.io.File(blobs.tmpDir, "interrupted").writeBytes(ByteArray(1000))
        blobs.commit(blobs.stage(ByteArrayInputStream("no row".toByteArray())))
        assertThat(blobs.list()).hasSize(2)

        repo.runMaintenance()
        assertThat(blobs.tmpDir.list().orEmpty()).isEmpty()
        assertThat(blobs.list()).containsExactly(kept.sha256)
        assertThat(repo.getFile(kept.id)).isNotNull()
    }

    @Test fun `import into an unknown or trashed folder is refused before copying`() = runBlocking<Unit> {
        val folder = repo.createFolder("F", null)
        repo.trash(emptyList(), listOf(folder))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { import(png, "x.png", folder) } }
        assertThat(blobs.list()).isEmpty()
    }

    @Test fun `trash, restore and delete a folder subtree with its files, blobs go only when unused`() = runBlocking<Unit> {
        val top = repo.createFolder("Top", null)
        val sub = repo.createFolder("Sub", top)
        val inSub = import(png, "p.png", sub).file()
        val loose = import("text".toByteArray(), "t.txt").file()
        val shared = import(png, "copy.png").file() // same blob as inSub, at the root

        repo.trash(emptyList(), listOf(top))
        assertThat(repo.observeFolders().first()).isEmpty()
        assertThat(repo.getFile(inSub.id)!!.isInTrash).isTrue()
        val trash = repo.observeTrash().first()
        assertThat(trash.folders.map { it.name }).containsExactly("Top")
        assertThat(trash.files).isEmpty() // the file is shown inside its trashed folder

        repo.restore(emptyList(), listOf(top))
        assertThat(repo.observeFolders().first().map { it.name to it.depth }).containsExactly("Top" to 0, "Sub" to 1).inOrder()
        assertThat(repo.getFile(inSub.id)!!.isInTrash).isFalse()

        repo.trash(emptyList(), listOf(top))
        repo.deletePermanently(emptyList(), listOf(top))
        assertThat(repo.getFile(inSub.id)).isNull()
        assertThat(repo.getFile(loose.id)).isNotNull()
        assertThat(blobs.exists(shared.sha256)).isTrue() // still used by "copy.png"

        repo.trash(listOf(shared.id, loose.id))
        repo.emptyTrash()
        assertThat(blobs.list()).isEmpty()
        assertThat(repo.observeSummary().first().fileCount).isEqualTo(0)
    }

    @Test fun `a restored file whose folder is gone or trashed goes to the root`() = runBlocking<Unit> {
        val folder = repo.createFolder("F", null)
        val f = import(png, "p.png", folder).file()
        repo.trash(listOf(f.id))
        repo.trash(emptyList(), listOf(folder))
        repo.restore(listOf(f.id))
        assertThat(repo.getFile(f.id)!!.folderId).isNull()
        assertThat(repo.getFile(f.id)!!.isInTrash).isFalse()
    }

    @Test fun `files trashed separately stay in the bin when their folder is restored`() = runBlocking<Unit> {
        val folder = repo.createFolder("F", null)
        val early = import(png, "early.png", folder).file()
        repo.trash(listOf(early.id))
        now += 1000
        repo.trash(emptyList(), listOf(folder))
        repo.restore(emptyList(), listOf(folder))
        assertThat(repo.getFile(early.id)!!.isInTrash).isTrue()
        assertThat(repo.observeTrash().first().files.map { it.id }).containsExactly(early.id)
    }

    @Test fun `expired trash is purged after the retention period`() = runBlocking<Unit> {
        val f = import(png, "p.png").file()
        repo.trash(listOf(f.id))
        now += TimeUnit.DAYS.toMillis(Limits.TRASH_RETENTION_DAYS.toLong()) - 1
        repo.purgeExpiredTrash()
        assertThat(repo.getFile(f.id)).isNotNull()
        now += 2
        repo.runMaintenance()
        assertThat(repo.getFile(f.id)).isNull()
        assertThat(blobs.list()).isEmpty()
    }

    @Test fun `folder rules - depth cap, no cycles, names sanitised`() = runBlocking<Unit> {
        val a = repo.createFolder(" A/B ", null)
        assertThat(repo.observeFolders().first().single().name).isEqualTo("A_B")
        val b = repo.createFolder("b", a)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.moveFolder(a, b) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.moveFolder(a, a) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.createFolder("   ", null) } }

        var parent = b
        repeat(Limits.MAX_FOLDER_DEPTH - 2) { parent = repo.createFolder("d$it", parent) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.createFolder("too deep", parent) } }
        val c = repo.createFolder("c", null)
        val cc = repo.createFolder("cc", c)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.moveFolder(c, parent) } }
        repo.moveFolder(cc, null)
        assertThat(repo.folderPath(parent).first().name).isEqualTo("A_B")
        assertThat(repo.folderPath(parent)).hasSize(Limits.MAX_FOLDER_DEPTH)
    }

    @Test fun `rename keeps the extension, move and search by name`() = runBlocking<Unit> {
        val f = import("x".toByteArray(), "Quarterly report.pdf").file()
        import("y".toByteArray(), "notes.txt")
        assertThat(repo.search("quart").first().map { it.id }).containsExactly(f.id)
        assertThat(repo.search("  ").first()).isEmpty()
        assertThat(repo.rename(f.id, "Annual")).isEqualTo("Annual.pdf")
        assertThat(repo.search("quart").first()).isEmpty()
        assertThat(repo.search("annu").first().map { it.displayName }).containsExactly("Annual.pdf")
        val folder = repo.createFolder("F", null)
        repo.moveFiles(listOf(f.id), folder)
        assertThat(repo.observeFiles(folder, FileSort()).first().map { it.id }).containsExactly(f.id)
        assertThat(repo.observeFolders().first().single().fileCount).isEqualTo(1)
        repo.trash(listOf(f.id))
        assertThat(repo.search("annu").first()).isEmpty()
    }

    @Test fun `sort orders`() = runBlocking<Unit> {
        import(ByteArray(30) { 1 }, "b.txt"); now++
        import(ByteArray(10) { 2 }, "a.txt"); now++
        import(ByteArray(20) { 3 }, "c.md")
        suspend fun names(s: FileSort) = repo.observeFiles(null, s).first().map { it.displayName }
        assertThat(names(FileSort(FileSortField.Name, true))).containsExactly("a.txt", "b.txt", "c.md").inOrder()
        assertThat(names(FileSort(FileSortField.DateAdded, false))).containsExactly("c.md", "a.txt", "b.txt").inOrder()
        assertThat(names(FileSort(FileSortField.Size, true))).containsExactly("a.txt", "c.md", "b.txt").inOrder()
        assertThat(names(FileSort(FileSortField.Type, true))).containsExactly("c.md", "a.txt", "b.txt").inOrder()
    }

    @Test fun `backlinks count notes and disappear with the note`() = runBlocking<Unit> {
        val f = import(png, "p.png").file()
        db.noteDao().upsert(
            NoteEntity(
                id = "n1", title = "Trip", previewText = "", folderId = null, isFavorite = false, isLocked = true,
                pageMode = PageMode.Pages, backgroundColor = 0, createdAt = 0, modifiedAt = 0, lastOpenedAt = 0, trashedAt = 5,
            ),
        )
        repo.replaceNoteRefs(NoteId("n1"), mapOf(f.id to mapOf(FileRefKind.Link to 2, FileRefKind.Inline to 1)))
        val usage = repo.observeUsage(f.id).first().single()
        assertThat(usage.noteTitle).isEqualTo("Trip")
        assertThat(usage.isLocked).isTrue()
        assertThat(usage.noteInTrash).isTrue()
        assertThat(usage.kinds).containsExactly(FileRefKind.Link, FileRefKind.Inline)
        assertThat(repo.usageCount(listOf(f.id))).isEqualTo(1)
        db.noteDao().delete(listOf("n1"))
        assertThat(repo.usageCount(listOf(f.id))).isEqualTo(0)
    }

    @Test fun `provider lookup serves live files only`() = runBlocking<Unit> {
        val f = import(png, "p.png").file()
        val served = repo.find(f.id.value)!!
        assertThat(served.displayName).isEqualTo("p.png")
        assertThat(served.blob.readBytes()).isEqualTo(png)
        assertThat(repo.contentUri(f).toString()).endsWith(".files/f/${f.id.value}/p.png")
        repo.trash(listOf(f.id))
        assertThat(repo.find(f.id.value)).isNull()
        assertThat(repo.find("not-an-id")).isNull()
    }
}

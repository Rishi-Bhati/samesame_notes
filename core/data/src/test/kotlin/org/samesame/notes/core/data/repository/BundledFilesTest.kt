package org.samesame.notes.core.data.repository

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
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
import org.samesame.notes.core.database.SameSameDatabase
import org.samesame.notes.core.database.entity.LibraryFileEntity
import org.samesame.notes.core.document.LinkedFileEntry
import org.samesame.notes.core.document.LinkedFileSource
import org.samesame.notes.core.files.BlobStore
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.FileSort
import org.samesame.notes.core.model.files.ImportResult
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/** The library side of `.ssnote` v3: bundling, restoring under Imported/<title>, same id / same content / id clash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundledFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dbA: SameSameDatabase
    private lateinit var dbB: SameSameDatabase
    private lateinit var a: DefaultFilesRepository
    private lateinit var b: DefaultFilesRepository
    private lateinit var blobsB: BlobStore

    private val pdf = "%PDF-1.4\n".toByteArray() + ByteArray(300) { it.toByte() }

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        dbA = Room.inMemoryDatabaseBuilder(app, SameSameDatabase::class.java).build()
        dbB = Room.inMemoryDatabaseBuilder(app, SameSameDatabase::class.java).build()
        a = DefaultFilesRepository(app, dbA.libraryDao(), BlobStore(tmp.newFolder("a"), freeSpace = { Long.MAX_VALUE / 2 }), Clock { 5L })
        blobsB = BlobStore(tmp.newFolder("b"), freeSpace = { Long.MAX_VALUE / 2 })
        b = DefaultFilesRepository(app, dbB.libraryDao(), blobsB, Clock { 6L })
    }

    @After fun tearDown() { dbA.close(); dbB.close() }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun fileOnA(bytes: ByteArray, name: String) =
        (a.importStream({ ByteArrayInputStream(bytes) }, name, null) as ImportResult.Imported).file

    private suspend fun restore(sources: List<LinkedFileSource>, title: String = "Trip") =
        b.restoreBundled(title, sources.map { it.entry }) { e -> sources.first { it.entry.id == e.id }.open() }

    @Test fun `sources list only stored files and carry the folder path`() = runBlocking<Unit> {
        val folder = a.createFolder("Work", null)
        val f = (a.importStream({ ByteArrayInputStream(pdf) }, "plan.pdf", folder) as ImportResult.Imported).file
        val sources = a.linkedFileSources(listOf(f.id, FileId("00000000-0000-4000-8000-000000000000")))
        assertThat(sources).hasSize(1)
        assertThat(sources[0].entry.folderPath).containsExactly("Work")
        assertThat(sources[0].entry.sha256).isEqualTo(sha(pdf))
        assertThat(sources[0].open().readBytes()).isEqualTo(pdf)
        assertThat(a.linkedFilesBytes(listOf(f.id))).isEqualTo(pdf.size.toLong())
    }

    @Test fun `a missing id is restored with its original id under Imported slash title`() = runBlocking<Unit> {
        val f = fileOnA(pdf, "plan.pdf")
        val remap = restore(a.linkedFileSources(listOf(f.id)))
        assertThat(remap).isEmpty()
        val restored = b.getFile(f.id)!!
        assertThat(restored.sha256).isEqualTo(sha(pdf))
        assertThat(restored.displayName).isEqualTo("plan.pdf")
        assertThat(blobsB.exists(sha(pdf))).isTrue()
        val path = b.folderPath(restored.folderId!!).map { it.name }
        assertThat(path).containsExactly("Imported", "Trip").inOrder()
    }

    @Test fun `same id and same content changes nothing, a lost blob is stored again`() = runBlocking<Unit> {
        val f = fileOnA(pdf, "plan.pdf")
        val sources = a.linkedFileSources(listOf(f.id))
        restore(sources)
        val folderCount = b.observeFolders().first().size
        assertThat(restore(sources)).isEmpty()
        assertThat(b.observeFolders().first()).hasSize(folderCount)
        assertThat(b.observeAllFiles(FileSort()).first()).hasSize(1)
        blobsB.delete(sha(pdf))
        assertThat(restore(sources)).isEmpty()
        assertThat(blobsB.exists(sha(pdf))).isTrue()
        assertThat(b.observeAllFiles(FileSort()).first()).hasSize(1)
    }

    @Test fun `same id with different content gets a new id which is returned for remapping`() = runBlocking<Unit> {
        val f = fileOnA(pdf, "plan.pdf")
        val sources = a.linkedFileSources(listOf(f.id))
        // library B already has another file under that id
        val other = "%PDF-1.7\nother".toByteArray()
        val s = other.let { sha(it) }
        dbB.libraryDao().insertFile(
            LibraryFileEntity(
                id = f.id.value, folderId = null, displayName = "mine.pdf", ext = "pdf", mimeType = "application/pdf",
                category = org.samesame.notes.core.model.files.FileCategory.Pdf, sizeBytes = other.size.toLong(), sha256 = s,
                createdAt = 1, modifiedAt = 1, sortIndex = 0,
            ),
        )
        val remap = restore(sources)
        assertThat(remap.keys).containsExactly(f.id.value)
        val newId = remap.getValue(f.id.value)
        assertThat(newId).isNotEqualTo(f.id.value)
        assertThat(b.getFile(f.id)!!.sha256).isEqualTo(s) // the existing file is untouched
        assertThat(b.getFile(FileId(newId))!!.sha256).isEqualTo(sha(pdf))
    }

    @Test fun `content that does not match its sha is skipped`() = runBlocking<Unit> {
        val f = fileOnA(pdf, "plan.pdf")
        val real = a.linkedFileSources(listOf(f.id)).single()
        val forged = LinkedFileSource(real.entry) { ByteArrayInputStream("tampered".toByteArray()) }
        assertThat(restore(listOf(forged))).isEmpty()
        assertThat(b.getFile(f.id)).isNull()
    }

    @Test fun `a malformed id is skipped`() = runBlocking<Unit> {
        val f = fileOnA(pdf, "plan.pdf")
        val real = a.linkedFileSources(listOf(f.id)).single()
        val bad = LinkedFileSource(real.entry.copy(id = "../../etc")) { pdf.inputStream() }
        restore(listOf(bad))
        assertThat(b.observeAllFiles(FileSort()).first()).isEmpty()
    }
}

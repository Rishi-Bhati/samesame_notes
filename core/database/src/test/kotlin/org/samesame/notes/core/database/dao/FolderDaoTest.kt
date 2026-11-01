package org.samesame.notes.core.database.dao

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.samesame.notes.core.database.SameSameDatabase
import org.samesame.notes.core.database.entity.FolderEntity
import org.samesame.notes.core.database.entity.NoteEntity
import org.samesame.notes.core.model.PageMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderDaoTest {
    private lateinit var db: SameSameDatabase
    private val folders get() = db.folderDao()
    private val notes get() = db.noteDao()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SameSameDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun folder(id: String, parentId: String? = null) = FolderEntity(
        id = id, parentId = parentId, name = id, color = null, sortOrder = 0,
        createdAt = 0, modifiedAt = 0, trashedAt = null,
    )

    private fun note(id: String, folderId: String?) = NoteEntity(
        id = id, title = id, previewText = "", folderId = folderId, isFavorite = false, isLocked = false,
        pageMode = PageMode.Pages, backgroundColor = 0, createdAt = 0, modifiedAt = 0, lastOpenedAt = 0, trashedAt = null,
    )

    /** p (top) > a > b, plus loose top-level folder "s"; notes n-p/n-a/n-b/n-s in them and n-root at the root. */
    private suspend fun seedTree() {
        folders.insert(folder("p"))
        folders.insert(folder("a", parentId = "p"))
        folders.insert(folder("b", parentId = "a"))
        folders.insert(folder("s"))
        notes.upsert(note("n-p", "p"))
        notes.upsert(note("n-a", "a"))
        notes.upsert(note("n-b", "b"))
        notes.upsert(note("n-s", "s"))
        notes.upsert(note("n-root", null))
    }

    @Test
    fun `subtree ids cover the folder itself and all descendants`() = runBlocking<Unit> {
        seedTree()

        assertThat(folders.subtreeIds(listOf("p"))).containsExactly("p", "a", "b")
        assertThat(folders.subtreeIds(listOf("a"))).containsExactly("a", "b")
        assertThat(folders.subtreeIds(listOf("s"))).containsExactly("s")
        assertThat(folders.subtreeIds(listOf("p", "a"))).containsExactly("p", "a", "b")
    }

    @Test
    fun `trash stamps the folders and their notes with one timestamp`() = runBlocking {
        seedTree()

        folders.trashSubtree(listOf("p"), 1000L)

        assertThat(folders.get("p")?.trashedAt).isEqualTo(1000L)
        assertThat(folders.get("a")?.trashedAt).isEqualTo(1000L)
        assertThat(folders.get("b")?.trashedAt).isEqualTo(1000L)
        assertThat(notes.get("n-p")?.trashedAt).isEqualTo(1000L)
        assertThat(notes.get("n-a")?.trashedAt).isEqualTo(1000L)
        assertThat(folders.get("s")?.trashedAt).isNull()
        assertThat(notes.get("n-s")?.trashedAt).isNull()
        assertThat(notes.get("n-root")?.trashedAt).isNull()
    }

    @Test
    fun `trashing a folder that contains a selected subfolder stamps everything once`() = runBlocking {
        seedTree()

        folders.trashSubtree(listOf("p", "a"), 2000L)

        assertThat(folders.get("p")?.trashedAt).isEqualTo(2000L)
        assertThat(folders.get("a")?.trashedAt).isEqualTo(2000L)
        assertThat(folders.get("b")?.trashedAt).isEqualTo(2000L)
    }

    @Test
    fun `trashed stats list the folders with their subtree note counts`() = runBlocking {
        seedTree()
        folders.trashSubtree(listOf("p"), 1000L)

        val trashed = folders.observeTrashedWithStats().first()
        assertThat(trashed.map { it.folder.id }).containsExactly("p", "a", "b")
        assertThat(trashed.first { it.folder.id == "p" }.noteCount).isEqualTo(3)
        assertThat(trashed.first { it.folder.id == "a" }.noteCount).isEqualTo(2)
    }

    @Test
    fun `restore brings the whole subtree back`() = runBlocking {
        seedTree()
        folders.trashSubtree(listOf("p"), 1000L)

        folders.restoreSubtree(listOf("p"))

        assertThat(folders.get("p")?.trashedAt).isNull()
        assertThat(folders.get("a")?.trashedAt).isNull()
        assertThat(folders.get("b")?.trashedAt).isNull()
        assertThat(notes.get("n-p")?.trashedAt).isNull()
        assertThat(notes.get("n-b")?.trashedAt).isNull()
        assertThat(folders.observeTrashedWithStats().first()).isEmpty()
    }

    @Test
    fun `restore keeps the original parent when it is live`() = runBlocking {
        seedTree()
        folders.trashSubtree(listOf("a"), 1000L)

        folders.restoreSubtree(listOf("a"))

        assertThat(folders.get("a")?.parentId).isEqualTo("p")
        assertThat(folders.depthOf("a")).isEqualTo(1)
    }

    @Test
    fun `restore goes to the top level when the original parent is trashed`() = runBlocking {
        seedTree()
        folders.trashSubtree(listOf("p"), 1000L)

        folders.restoreSubtree(listOf("a"))

        assertThat(folders.get("a")?.parentId).isNull()
        assertThat(folders.get("b")?.parentId).isEqualTo("a")
        assertThat(folders.depthOf("a")).isEqualTo(0)
        assertThat(folders.depthOf("b")).isEqualTo(1)
        assertThat(folders.get("p")?.trashedAt).isEqualTo(1000L)
        assertThat(notes.get("n-a")?.trashedAt).isNull()
    }

    @Test
    fun `restore goes to the top level when the original parent is gone`() = runBlocking {
        seedTree()
        folders.trashSubtree(listOf("a"), 1000L)
        folders.delete(listOf("p"))

        folders.restoreSubtree(listOf("a"))

        assertThat(folders.get("a")?.parentId).isNull()
        assertThat(folders.get("b")?.parentId).isEqualTo("a")
        assertThat(folders.depthOf("a")).isEqualTo(0)
    }

    @Test
    fun `delete removes the rows and the closure links`() = runBlocking {
        seedTree()

        folders.delete(listOf("p"))

        assertThat(folders.get("p")).isNull()
        assertThat(folders.depthOf("p")).isEqualTo(-1)
        assertThat(folders.depthOf("a")).isEqualTo(0)
        assertThat(folders.depthOf("b")).isEqualTo(1)
    }

    @Test
    fun `trashed before lists only the expired folders`() = runBlocking<Unit> {
        seedTree()
        folders.trashSubtree(listOf("p"), 1000L)
        folders.trashSubtree(listOf("s"), 5000L)

        assertThat(folders.trashedBefore(3000L)).containsExactly("p", "a", "b")
    }

    @Test
    fun `note ids in folders cover the given folders only`() = runBlocking<Unit> {
        seedTree()

        assertThat(folders.noteIdsIn(listOf("p", "a"))).containsExactly("n-p", "n-a")
    }
}

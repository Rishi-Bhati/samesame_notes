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
import org.samesame.notes.core.database.entity.NoteEntity
import org.samesame.notes.core.model.PageMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteDaoCountsTest {
    private lateinit var db: SameSameDatabase
    private val notes get() = db.noteDao()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SameSameDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun note(id: String, folderId: String?, favorite: Boolean, locked: Boolean, trashedAt: Long? = null) = NoteEntity(
        id = id, title = id, previewText = "", folderId = folderId, isFavorite = favorite, isLocked = locked,
        pageMode = PageMode.Pages, backgroundColor = 0, createdAt = 0, modifiedAt = 0, lastOpenedAt = 0, trashedAt = trashedAt,
    )

    @Test
    fun `counts cover all root favorites and locked outside trash`() = runBlocking {
        notes.upsert(note("plain-root", null, favorite = false, locked = false))
        notes.upsert(note("fav-root", null, favorite = true, locked = false))
        notes.upsert(note("fav-folder", "f", favorite = true, locked = false))
        notes.upsert(note("locked-folder", "f", favorite = false, locked = true))
        notes.upsert(note("fav-locked", null, favorite = true, locked = true))
        notes.upsert(note("trashed-fav-locked", null, favorite = true, locked = true, trashedAt = 1000L))

        val counts = notes.observeCounts().first()

        // The trashed note is excluded from every count.
        assertThat(counts.allCount).isEqualTo(5)
        assertThat(counts.rootCount).isEqualTo(3)
        assertThat(counts.favoritesCount).isEqualTo(3)
        assertThat(counts.lockedCount).isEqualTo(2)
    }
}

package org.samesame.notes.core.database

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.samesame.notes.core.database.entity.NoteSearchEntity
import java.io.File

/** After a note's search row is deleted and the maintenance runs, its words are gone from the database files (M5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMaintenanceTest {
    private lateinit var db: SameSameDatabase
    private lateinit var file: File

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        file = context.getDatabasePath("maintenance-test.db")
        file.parentFile?.mkdirs()
        db = Room.databaseBuilder(context, SameSameDatabase::class.java, file.path)
            .addCallback(DatabaseMaintenance.SecureDeleteCallback)
            .build()
    }

    @After fun tearDown() = db.close()

    private fun dbBytes(): String =
        listOf(file, File(file.path + "-wal"), File(file.path + "-journal"))
            .filter { it.isFile }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }

    @Test fun deletedSearchTextIsScrubbed() = runBlocking<Unit> {
        val search = db.searchDao()
        repeat(20) { search.insert(NoteSearchEntity("other$it", "title $it", "filler body text number $it", "")) }
        search.insert(NoteSearchEntity("n1", "zebracorntitle", "myverysecretpassphrase", ""))
        val maintenance = DatabaseMaintenance(db)
        maintenance.purgeDeletedText()
        assertThat(dbBytes()).contains("myverysecretpassphrase")
        maintenance.secureDelete()
        search.deleteForNote("n1")
        maintenance.purgeDeletedText()
        val bytes = dbBytes()
        assertThat(bytes).doesNotContain("myverysecretpassphrase")
        assertThat(bytes).doesNotContain("zebracorntitle")
        maintenance.vacuum()
        assertThat(dbBytes()).doesNotContain("myverysecretpassphrase")
    }
}

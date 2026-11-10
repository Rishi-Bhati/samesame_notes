package org.samesame.notes.core.database.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.database.SameSameDatabase

/**
 * Room v2 -> v3 with MigrationTestHelper on a device (`connectedDebugAndroidTest`; the JVM twin is Migration2To3Test).
 * The helper builds v2 from the exported schema, runs MIGRATION_2_3 and validates the result against `3.json`.
 */
@RunWith(AndroidJUnit4::class)
class Migration2To3HelperTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SameSameDatabase::class.java)

    @Test
    fun migrate2To3KeepsNotes() {
        helper.createDatabase(DB, 2).apply {
            execSQL("INSERT INTO folders VALUES ('f1', NULL, 'Work', NULL, 0, 1, 2, NULL, 1)")
            execSQL("INSERT INTO folder_closure VALUES ('f1','f1',0)")
            execSQL("INSERT INTO notes VALUES ('n1', 'Shopping', 'milk', 'f1', 1, 0, 'Pages', 0, 10, 20, 30, NULL, 1)")
            execSQL("INSERT INTO note_search (note_id, title, body, ink_text) VALUES ('n1', 'Shopping', 'milk', '')")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 3, true, SameSameDatabase.MIGRATION_2_3)
        db.query("SELECT title, folder_id FROM notes WHERE id = 'n1'").use { c ->
            c.moveToFirst()
            assertEquals("Shopping", c.getString(0))
            assertEquals("f1", c.getString(1))
        }
        db.query("SELECT COUNT(*) FROM library_files").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    private companion object {
        const val DB = "migration-helper-test.db"
    }
}

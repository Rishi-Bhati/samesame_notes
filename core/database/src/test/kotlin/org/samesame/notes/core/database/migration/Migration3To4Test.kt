package org.samesame.notes.core.database.migration

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.samesame.notes.core.database.SameSameDatabase
import java.io.File

/**
 * Room v3 -> v4 on the JVM: builds a database exactly as v3 shipped it (from the committed `3.json`), fills it with
 * notes, folders, tags, Files folders, files and backlinks, then opens it with [SameSameDatabase.MIGRATION_3_4]. Room
 * validates the result against the v4 entities, and every v3 row must still be there unchanged. The new column gets
 * the empty default; the new `file_tags` table works and cascades with its file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration3To4Test {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var dbFile: File
    private var room: SameSameDatabase? = null

    private val fileId = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @Before fun setUp() {
        dbFile = context.getDatabasePath("migration-3-4-test.db")
        dbFile.parentFile!!.mkdirs()
        SQLiteDatabase.deleteDatabase(dbFile)
    }

    @After fun tearDown() {
        room?.close()
        SQLiteDatabase.deleteDatabase(dbFile)
    }

    private fun schema(version: Int): JSONObject =
        JSONObject(File("schemas/org.samesame.notes.core.database.SameSameDatabase/$version.json").readText()).getJSONObject("database")

    /** Creates the v3 database from its exported schema, as an installed 0.2.1 would have it. */
    private fun createV3(seed: SQLiteDatabase.() -> Unit) {
        val v3 = schema(3)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            val entities = v3.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx ->
                    for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                e.optJSONArray("contentSyncTriggers")?.let { triggers ->
                    for (j in 0 until triggers.length()) db.execSQL(triggers.getString(j))
                }
            }
            val setup = v3.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.seed()
            db.version = 3
        }
    }

    private fun SQLiteDatabase.seedAll() {
        execSQL("INSERT INTO folders VALUES ('f1', NULL, 'Work', 4278190335, 0, 1, 2, NULL, 1)")
        execSQL(
            "INSERT INTO notes VALUES ('n1', 'Shopping', 'milk, eggs', 'f1', 1, 0, 'Pages', 4294967295, 10, 20, 30, NULL, 1)",
        )
        execSQL("INSERT INTO tags VALUES ('home', 'Home')")
        execSQL("INSERT INTO note_tags VALUES ('n1', 'home')")
        execSQL("INSERT INTO note_search (note_id, title, body, ink_text) VALUES ('n1', 'Shopping', 'milk eggs', 'bread')")
        execSQL("INSERT INTO file_folders VALUES ('ff1', 'Trips', NULL, NULL, 1024, 5, 6, NULL)")
        execSQL(
            "INSERT INTO library_files VALUES ('$fileId', 'ff1', 'Quarterly report.pdf', 'pdf', 'application/pdf', 'Pdf', 10, " +
                "'${"a".repeat(64)}', NULL, NULL, NULL, NULL, NULL, 7, 8, 1024, 1, NULL)",
        )
        execSQL("INSERT INTO file_refs VALUES ('n1', '$fileId', 'Link', 2)")
    }

    private fun dump(db: SQLiteDatabase, sql: String): List<List<String?>> = db.rawQuery(sql, null).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
    }

    private val oldTables = listOf(
        "SELECT * FROM notes ORDER BY id", "SELECT * FROM folders ORDER BY id", "SELECT * FROM tags",
        "SELECT * FROM note_tags", "SELECT note_id, title, body, ink_text FROM note_search", "SELECT * FROM file_folders",
        "SELECT id, folder_id, display_name, ext, mime_type, category, size_bytes, sha256, width, height, duration_ms, " +
            "page_count, source_app, created_at, modified_at, sort_index, is_favorite, trashed_at FROM library_files",
        "SELECT * FROM file_refs",
    )

    private fun openRoom(): SameSameDatabase = Room.databaseBuilder(context, SameSameDatabase::class.java, dbFile.absolutePath)
        .addMigrations(SameSameDatabase.MIGRATION_1_2, SameSameDatabase.MIGRATION_2_3, SameSameDatabase.MIGRATION_3_4)
        .build().also { room = it }

    @Test fun `migration 3 to 4 validates and keeps every note, folder, tag and file row`() = runBlocking<Unit> {
        createV3 { seedAll() }
        val before = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db -> oldTables.map { dump(db, it) } }

        val db = openRoom()
        assertThat(db.noteDao().get("n1")).isNotNull()
        assertThat(db.libraryDao().file(fileId)?.description).isEmpty()
        assertThat(db.libraryDao().files(listOf(fileId))).hasSize(1)
        db.close()
        room = null

        val after = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertThat(raw.version).isEqualTo(4)
            assertThat(dump(raw, "SELECT description FROM library_files WHERE id = '$fileId'")).containsExactly(listOf(""))
            oldTables.map { dump(raw, it) }
        }
        assertThat(after).isEqualTo(before)
    }

    @Test fun `the new description and tags work after migrating and tags go with their file`() = runBlocking<Unit> {
        createV3 { seedAll() }
        val db = openRoom()
        val lib = db.libraryDao()
        lib.setDescription(fileId, "Tax papers for 2025")
        assertThat(lib.file(fileId)?.description).isEqualTo("Tax papers for 2025")

        lib.replaceTags(fileId, listOf("tax", "2025"))
        assertThat(lib.observeLiveTags().first()).containsExactly("2025", "tax")
        assertThat(lib.searchByTag("ta").first().map { it.id }).containsExactly(fileId)
        assertThat(lib.tagsFor(listOf(fileId)).map { it.tag }).containsExactly("2025", "tax")

        // Tags of a file in the Recycle bin are not offered as filter chips.
        lib.trashLiveFiles(listOf(fileId), 99)
        assertThat(lib.observeLiveTags().first()).isEmpty()
        lib.restoreFiles(listOf(fileId))
        assertThat(lib.observeLiveTags().first()).hasSize(2)

        lib.deleteFiles(listOf(fileId))
        assertThat(lib.tagsFor(listOf(fileId))).isEmpty()
        db.close()
        room = null
    }

    @Test fun `a v3 database without the migration is not opened and not wiped`() {
        createV3 { seedAll() }
        val db = Room.databaseBuilder(context, SameSameDatabase::class.java, dbFile.absolutePath)
            .addMigrations(SameSameDatabase.MIGRATION_1_2, SameSameDatabase.MIGRATION_2_3).build().also { room = it }
        assertThrows(IllegalStateException::class.java) { runBlocking { db.libraryDao().file(fileId) } }
        db.close()
        room = null
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertThat(dump(raw, "SELECT id FROM library_files")).containsExactly(listOf(fileId))
        }
    }

    @Test fun `the migration is additive only`() {
        val statements = SameSameDatabase.MIGRATION_3_4_STATEMENTS.map { it.trim().uppercase() }
        assertThat(statements).hasSize(3)
        assertThat(statements[0]).startsWith("ALTER TABLE `LIBRARY_FILES` ADD COLUMN")
        assertThat(statements.drop(1).all { it.startsWith("CREATE ") }).isTrue()
        listOf("DROP", "RENAME").forEach { word ->
            statements.forEach { s -> assertThat(s).doesNotContain(word) }
        }
    }

    @Test fun `the committed v4 schema matches the new table and column`() {
        val v4 = schema(4)
        val entities = v4.getJSONArray("entities")
        val byName = (0 until entities.length()).map { entities.getJSONObject(it) }.associateBy { it.getString("tableName") }
        val files = byName.getValue("library_files")
        assertThat(files.getString("createSql")).contains("`description` TEXT NOT NULL DEFAULT ''")
        assertThat(files.getString("createSql")).contains("`is_favorite` INTEGER NOT NULL DEFAULT 0")
        val tags = byName.getValue("file_tags")
        assertThat(tags.getString("createSql").replace("\${TABLE_NAME}", "file_tags"))
            .isEqualTo(SameSameDatabase.MIGRATION_3_4_STATEMENTS[1])
        val index = tags.getJSONArray("indices").getJSONObject(0).getString("createSql").replace("\${TABLE_NAME}", "file_tags")
        assertThat(index).isEqualTo(SameSameDatabase.MIGRATION_3_4_STATEMENTS[2])
    }
}

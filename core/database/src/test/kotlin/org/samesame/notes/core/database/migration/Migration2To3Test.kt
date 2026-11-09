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
import org.samesame.notes.core.database.entity.FileRefEntity
import org.samesame.notes.core.database.entity.LibraryFileEntity
import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.FileRefKind
import java.io.File

/**
 * Room v2 -> v3 on the JVM: a database is built exactly as v2 shipped it (from the committed `2.json`), filled with
 * notes, folders, tags, search text and view state, then opened by the current Room with [SameSameDatabase.MIGRATION_2_3].
 * Room validates every table against the v3 entities after migrating (the same check MigrationTestHelper runs), and
 * every v2 row must still be there unchanged. The instrumented `Migration2To3HelperTest` covers the same on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration2To3Test {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var dbFile: File
    private var room: SameSameDatabase? = null

    @Before fun setUp() {
        dbFile = context.getDatabasePath("migration-test.db")
        dbFile.parentFile!!.mkdirs()
        SQLiteDatabase.deleteDatabase(dbFile)
    }

    @After fun tearDown() {
        room?.close()
        SQLiteDatabase.deleteDatabase(dbFile)
    }

    private fun schema(version: Int): JSONObject =
        JSONObject(File("schemas/org.samesame.notes.core.database.SameSameDatabase/$version.json").readText()).getJSONObject("database")

    /** Creates the v2 database from its exported schema, as an installed 0.1.x would have it. */
    private fun createV2(seed: SQLiteDatabase.() -> Unit) {
        val v2 = schema(2)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            val entities = v2.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx ->
                    for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = v2.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.seed()
            db.version = 2
        }
    }

    private fun SQLiteDatabase.seedNotes() {
        execSQL("INSERT INTO folders VALUES ('f1', NULL, 'Work', 4278190335, 0, 1, 2, NULL, 1)")
        execSQL("INSERT INTO folders VALUES ('f2', 'f1', 'Sub', NULL, 1, 3, 4, NULL, 0)")
        execSQL("INSERT INTO folder_closure VALUES ('f1','f1',0), ('f2','f2',0), ('f1','f2',1)")
        execSQL(
            "INSERT INTO notes VALUES ('n1', 'Shopping', 'milk, eggs', 'f1', 1, 0, 'Pages', 4294967295, 10, 20, 30, NULL, 1)," +
                " ('n2', 'Secret', '', 'f2', 0, 1, 'Pages', 0, 11, 21, 31, NULL, 0)," +
                " ('n3', 'Old', 'gone soon', NULL, 0, 0, 'Pages', 0, 12, 22, 32, 99, 1)",
        )
        execSQL("INSERT INTO tags VALUES ('home', 'Home')")
        execSQL("INSERT INTO note_tags VALUES ('n1', 'home')")
        execSQL("INSERT INTO note_search (note_id, title, body, ink_text) VALUES ('n1', 'Shopping', 'milk eggs', 'bread')")
        execSQL("INSERT INTO note_view_state VALUES ('n1', 3, 0.5, 1.25)")
    }

    private fun dump(db: SQLiteDatabase, sql: String): List<List<String?>> = db.rawQuery(sql, null).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
    }

    private val oldTables = listOf(
        "SELECT * FROM notes ORDER BY id", "SELECT * FROM folders ORDER BY id",
        "SELECT * FROM folder_closure ORDER BY ancestor, descendant", "SELECT * FROM tags", "SELECT * FROM note_tags",
        "SELECT note_id, title, body, ink_text FROM note_search", "SELECT * FROM note_view_state",
    )

    private fun openRoom(): SameSameDatabase = Room.databaseBuilder(context, SameSameDatabase::class.java, dbFile.absolutePath)
        .addMigrations(SameSameDatabase.MIGRATION_1_2, SameSameDatabase.MIGRATION_2_3)
        .build().also { room = it }

    @Test fun `migration 2 to 3 validates and keeps every note, folder, tag, search entry and view state`() = runBlocking<Unit> {
        createV2 { seedNotes() }
        val before = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db -> oldTables.map { dump(db, it) } }
        assertThat(before.first()).hasSize(3)

        val db = openRoom()
        // Opening runs the migration and Room's schema validation (throws on any mismatch with the v3 entities).
        assertThat(db.noteDao().let { dao -> listOf("n1", "n2", "n3").mapNotNull { dao.get(it) } }).hasSize(3)
        assertThat(db.searchDao().search("\"milk*\"").first().map { it.id }).containsExactly("n1")
        db.close()
        room = null

        val after = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertThat(raw.version).isEqualTo(3)
            oldTables.map { dump(raw, it) }
        }
        assertThat(after).isEqualTo(before)
    }

    @Test fun `the new tables work after migrating - FTS triggers, backlinks cascade with notes`() = runBlocking<Unit> {
        createV2 { seedNotes() }
        val db = openRoom()
        val lib = db.libraryDao()
        lib.insertFile(
            LibraryFileEntity(
                id = "0f8fad5b-d9cb-469f-a165-70867728950e", folderId = null, displayName = "Quarterly report.pdf", ext = "pdf",
                mimeType = "application/pdf", category = FileCategory.Pdf, sizeBytes = 10, sha256 = "a".repeat(64),
                createdAt = 1, modifiedAt = 1, sortIndex = 1024,
            ),
        )
        assertThat(lib.search("\"quart*\"").first().map { it.displayName }).containsExactly("Quarterly report.pdf")
        lib.renameFile("0f8fad5b-d9cb-469f-a165-70867728950e", "Annual.pdf", "pdf", 2)
        assertThat(lib.search("\"quart*\"").first()).isEmpty()
        assertThat(lib.search("\"annual*\"").first()).hasSize(1)

        lib.replaceRefs("n1", listOf(FileRefEntity("n1", "0f8fad5b-d9cb-469f-a165-70867728950e", FileRefKind.Link, 2)))
        assertThat(lib.usageCount(listOf("0f8fad5b-d9cb-469f-a165-70867728950e"))).isEqualTo(1)
        db.noteDao().delete(listOf("n1"))
        assertThat(lib.usageCount(listOf("0f8fad5b-d9cb-469f-a165-70867728950e"))).isEqualTo(0)
    }

    @Test fun `the migration only creates - it has no statement that could alter or drop existing data`() {
        val statements = SameSameDatabase.MIGRATION_2_3_STATEMENTS.map { it.trim().uppercase() }
        assertThat(statements.all { it.startsWith("CREATE ") }).isTrue()
        val oldNames = listOf("`NOTES`", "`FOLDERS`", "`FOLDER_CLOSURE`", "`TAGS`", "`NOTE_TAGS`", "`NOTE_SEARCH`", "`NOTE_VIEW_STATE`")
        // Old tables are only ever referenced as foreign-key targets (file_refs -> notes).
        statements.forEach { s ->
            oldNames.forEach { name -> if (name in s) assertThat(s).contains("REFERENCES $name") }
        }
    }

    @Test fun `without the migration a v2 database is not opened (and not wiped)`() {
        createV2 { seedNotes() }
        val db = Room.databaseBuilder(context, SameSameDatabase::class.java, dbFile.absolutePath)
            .addMigrations(SameSameDatabase.MIGRATION_1_2).build().also { room = it }
        assertThrows(IllegalStateException::class.java) { runBlocking { db.noteDao().get("n1") } }
        db.close()
        room = null
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertThat(dump(raw, "SELECT id FROM notes")).hasSize(3)
        }
    }

    @Test fun `the committed v3 schema matches the migration statements`() {
        val v3 = schema(3)
        val expected = buildList {
            val entities = v3.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                if (table !in setOf("file_folders", "library_files", "library_files_fts", "file_refs")) continue
                add(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx -> for (j in 0 until idx.length()) add(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
                e.optJSONArray("contentSyncTriggers")?.let { t -> for (j in 0 until t.length()) add(t.getString(j)) }
            }
        }
        assertThat(SameSameDatabase.MIGRATION_2_3_STATEMENTS).containsExactlyElementsIn(expected)
    }
}

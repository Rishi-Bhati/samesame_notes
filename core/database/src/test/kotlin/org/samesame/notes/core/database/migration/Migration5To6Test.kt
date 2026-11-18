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
import org.samesame.notes.core.database.entity.NoteEntity
import org.samesame.notes.core.model.NoteKind
import org.samesame.notes.core.model.PageMode
import java.io.File

/**
 * Room v5 -> v6 on the JVM, following Migration4To5Test: build a database exactly as v5 shipped it from the committed
 * `5.json`, fill it with notes, then open it with [SameSameDatabase.MIGRATION_5_6]. Room validates the result against
 * the v6 entities. Every v5 row must survive unchanged; the new kind column starts at Text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration5To6Test {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var dbFile: File
    private var room: SameSameDatabase? = null

    @Before fun setUp() {
        dbFile = context.getDatabasePath("migration-5-6-test.db")
        dbFile.parentFile!!.mkdirs()
        SQLiteDatabase.deleteDatabase(dbFile)
    }

    @After fun tearDown() {
        room?.close()
        SQLiteDatabase.deleteDatabase(dbFile)
    }

    private fun schema(version: Int): JSONObject =
        JSONObject(File("schemas/org.samesame.notes.core.database.SameSameDatabase/$version.json").readText()).getJSONObject("database")

    /** Creates the v5 database from its exported schema, as an installed 0.3.0-alpha would have it. */
    private fun createV5(seed: SQLiteDatabase.() -> Unit) {
        val v5 = schema(5)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            val entities = v5.getJSONArray("entities")
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
            val setup = v5.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.seed()
            db.version = 5
        }
    }

    private fun SQLiteDatabase.seedAll() {
        execSQL("INSERT INTO folders VALUES ('f1', NULL, 'Work', 4278190335, 0, 1, 2, NULL, 1)")
        execSQL(
            "INSERT INTO notes VALUES ('n1', 'Shopping', 'milk, eggs', 'f1', 1, 0, 'Pages', 4294967295, 10, 20, 30, NULL, 1, 3, 5)",
        )
        execSQL("INSERT INTO notes VALUES ('n2', 'Hours', '7h 30m', NULL, 0, 0, 'Infinite', 4294967295, 11, 21, 31, NULL, 1, 0, 0)")
        execSQL("INSERT INTO tags VALUES ('home', 'Home')")
        execSQL("INSERT INTO note_tags VALUES ('n1', 'home')")
    }

    private fun dump(db: SQLiteDatabase, sql: String): List<List<String?>> = db.rawQuery(sql, null).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
    }

    /** The v5 columns of `notes`, so the comparison ignores the new column. */
    private val oldTables = listOf(
        "SELECT id, title, preview_text, folder_id, is_favorite, is_locked, page_mode, background_color, created_at, " +
            "modified_at, last_opened_at, trashed_at, is_dirty, checklist_done, checklist_total FROM notes ORDER BY id",
        "SELECT * FROM folders ORDER BY id", "SELECT * FROM tags", "SELECT * FROM note_tags",
    )

    private fun openRoom(): SameSameDatabase = Room.databaseBuilder(context, SameSameDatabase::class.java, dbFile.absolutePath)
        .addMigrations(
            SameSameDatabase.MIGRATION_1_2, SameSameDatabase.MIGRATION_2_3,
            SameSameDatabase.MIGRATION_3_4, SameSameDatabase.MIGRATION_4_5, SameSameDatabase.MIGRATION_5_6,
        )
        .build().also { room = it }

    @Test fun `migration 5 to 6 validates and keeps every row as a Text note`() = runBlocking<Unit> {
        createV5 { seedAll() }
        val before = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db -> oldTables.map { dump(db, it) } }

        val db = openRoom()
        val note = db.noteDao().get("n1")!!
        assertThat(note.title).isEqualTo("Shopping")
        assertThat(note.checklistTotal).isEqualTo(5)
        assertThat(note.kind).isEqualTo(NoteKind.Text)

        // New Markdown note inserts and the by-kind query only returns it.
        db.noteDao().upsert(
            NoteEntity("m1", "Readme", "", null, false, false, PageMode.Infinite, 0, 1, 2, 3, null, kind = NoteKind.Markdown),
        )
        assertThat(db.noteDao().observeByKind(NoteKind.Markdown).first().map { it.id }).containsExactly("m1")
        assertThat(db.noteDao().observeByKind(NoteKind.Text).first().map { it.id }).containsExactly("n1", "n2")
        db.close()
        room = null

        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertThat(raw.version).isEqualTo(6)
            assertThat(dump(raw, "SELECT id, kind FROM notes ORDER BY id"))
                .containsExactly(listOf("m1", "Markdown"), listOf("n1", "Text"), listOf("n2", "Text"))
            assertThat(oldTables.map { dump(raw, it.replace("FROM notes ORDER BY id", "FROM notes WHERE id <> 'm1' ORDER BY id")) })
                .isEqualTo(before)
        }
    }

    @Test fun `a v5 database without the migration is not opened and not wiped`() {
        createV5 { seedAll() }
        val db = Room.databaseBuilder(context, SameSameDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                SameSameDatabase.MIGRATION_1_2, SameSameDatabase.MIGRATION_2_3,
                SameSameDatabase.MIGRATION_3_4, SameSameDatabase.MIGRATION_4_5,
            )
            .build().also { room = it }
        assertThrows(IllegalStateException::class.java) { runBlocking { db.noteDao().get("n1") } }
        db.close()
        room = null
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertThat(dump(raw, "SELECT id FROM notes ORDER BY id")).containsExactly(listOf("n1"), listOf("n2"))
        }
    }

    @Test fun `the migration is additive only`() {
        val statements = SameSameDatabase.MIGRATION_5_6_STATEMENTS.map { it.trim().uppercase() }
        assertThat(statements).hasSize(1)
        statements.forEach { s ->
            assertThat(s).startsWith("ALTER TABLE `NOTES` ADD COLUMN")
            listOf("DROP", "RENAME").forEach { word -> assertThat(s).doesNotContain(word) }
        }
    }

    @Test fun `the committed v6 schema has the new column`() {
        val entities = schema(6).getJSONArray("entities")
        val notes = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == "notes" }
        assertThat(notes.getString("createSql")).contains("`kind` TEXT NOT NULL DEFAULT 'Text'")
    }
}

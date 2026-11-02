package org.samesame.notes.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import org.samesame.notes.core.database.dao.FolderDao
import org.samesame.notes.core.database.dao.NoteDao
import org.samesame.notes.core.database.dao.NoteViewStateDao
import org.samesame.notes.core.database.dao.SearchDao
import org.samesame.notes.core.database.dao.TagDao
import org.samesame.notes.core.database.entity.FolderClosureEntity
import org.samesame.notes.core.database.entity.FolderEntity
import org.samesame.notes.core.database.entity.NoteEntity
import org.samesame.notes.core.database.entity.NoteSearchEntity
import org.samesame.notes.core.database.entity.NoteTagCrossRef
import org.samesame.notes.core.database.entity.NoteViewStateEntity
import org.samesame.notes.core.database.entity.TagEntity

@Database(
    entities = [
        NoteEntity::class,
        FolderEntity::class,
        FolderClosureEntity::class,
        TagEntity::class,
        NoteTagCrossRef::class,
        NoteSearchEntity::class,
        NoteViewStateEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SameSameDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao
    abstract fun tagDao(): TagDao
    abstract fun searchDao(): SearchDao
    abstract fun viewStateDao(): NoteViewStateDao

    companion object {
        /** v2 adds the per-note reading position (`note_view_state`), kept out of the document bundle. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_view_state` (" +
                        "`note_id` TEXT NOT NULL, `page_index` INTEGER NOT NULL, " +
                        "`offset_in_page` REAL NOT NULL, `zoom` REAL NOT NULL, PRIMARY KEY(`note_id`))",
                )
            }
        }
    }
}

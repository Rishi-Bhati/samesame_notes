package org.samesame.notes.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import org.samesame.notes.core.database.dao.FolderDao
import org.samesame.notes.core.database.dao.LibraryDao
import org.samesame.notes.core.database.dao.NoteDao
import org.samesame.notes.core.database.dao.NoteViewStateDao
import org.samesame.notes.core.database.dao.SearchDao
import org.samesame.notes.core.database.dao.TagDao
import org.samesame.notes.core.database.entity.FolderClosureEntity
import org.samesame.notes.core.database.entity.FileFolderEntity
import org.samesame.notes.core.database.entity.FileRefEntity
import org.samesame.notes.core.database.entity.FileTagEntity
import org.samesame.notes.core.database.entity.FolderEntity
import org.samesame.notes.core.database.entity.LibraryFileEntity
import org.samesame.notes.core.database.entity.LibraryFileFtsEntity
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
        FileFolderEntity::class,
        LibraryFileEntity::class,
        LibraryFileFtsEntity::class,
        FileTagEntity::class,
        FileRefEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class SameSameDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao
    abstract fun tagDao(): TagDao
    abstract fun searchDao(): SearchDao
    abstract fun viewStateDao(): NoteViewStateDao
    abstract fun libraryDao(): LibraryDao

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
    
        /**
         * v3 adds the Files library (docs/architecture/files-feature.md 1.2). Purely additive: it only creates new tables,
         * indices and FTS triggers, and never touches the note, folder, tag, search or view-state tables, so an upgrade
         * cannot lose notes. The statements are the ones Room generated in `schemas/.../3.json`.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                MIGRATION_2_3_STATEMENTS.forEach(connection::execSQL)
            }
        }

        internal val MIGRATION_2_3_STATEMENTS = listOf(
            "CREATE TABLE IF NOT EXISTS `file_folders` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `parent_id` TEXT, " +
                "`color` INTEGER, `sort_index` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `modified_at` INTEGER NOT NULL, " +
                "`trashed_at` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`parent_id`) REFERENCES `file_folders`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_file_folders_parent_id_trashed_at` ON `file_folders` (`parent_id`, `trashed_at`)",
            "CREATE TABLE IF NOT EXISTS `library_files` (`id` TEXT NOT NULL, `folder_id` TEXT, `display_name` TEXT NOT NULL, " +
                "`ext` TEXT NOT NULL, `mime_type` TEXT NOT NULL, `category` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, " +
                "`sha256` TEXT NOT NULL, `width` INTEGER, `height` INTEGER, `duration_ms` INTEGER, `page_count` INTEGER, " +
                "`source_app` TEXT, `created_at` INTEGER NOT NULL, `modified_at` INTEGER NOT NULL, `sort_index` INTEGER NOT NULL, " +
                "`is_favorite` INTEGER NOT NULL DEFAULT 0, `trashed_at` INTEGER, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`folder_id`) REFERENCES `file_folders`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE INDEX IF NOT EXISTS `index_library_files_folder_id_trashed_at` ON `library_files` (`folder_id`, `trashed_at`)",
            "CREATE INDEX IF NOT EXISTS `index_library_files_sha256` ON `library_files` (`sha256`)",
            "CREATE VIRTUAL TABLE IF NOT EXISTS `library_files_fts` USING FTS4(`display_name` TEXT NOT NULL, " +
                "tokenize=unicode61, content=`library_files`)",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_files_fts_BEFORE_UPDATE BEFORE UPDATE ON `library_files` " +
                "BEGIN DELETE FROM `library_files_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_files_fts_BEFORE_DELETE BEFORE DELETE ON `library_files` " +
                "BEGIN DELETE FROM `library_files_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_files_fts_AFTER_UPDATE AFTER UPDATE ON `library_files` " +
                "BEGIN INSERT INTO `library_files_fts`(`docid`, `display_name`) VALUES (NEW.`rowid`, NEW.`display_name`); END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_files_fts_AFTER_INSERT AFTER INSERT ON `library_files` " +
                "BEGIN INSERT INTO `library_files_fts`(`docid`, `display_name`) VALUES (NEW.`rowid`, NEW.`display_name`); END",
            "CREATE TABLE IF NOT EXISTS `file_refs` (`note_id` TEXT NOT NULL, `file_id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`count` INTEGER NOT NULL, PRIMARY KEY(`note_id`, `file_id`, `kind`), FOREIGN KEY(`note_id`) " +
                "REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_file_refs_file_id` ON `file_refs` (`file_id`)",
        )

        /**
         * v4 adds a file's short description and its tags (rel022-filesorder). Purely additive: one column with an empty
         * default and one new table, so no existing note, folder or file row is rewritten or lost.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                MIGRATION_3_4_STATEMENTS.forEach(connection::execSQL)
            }
        }

        internal val MIGRATION_3_4_STATEMENTS = listOf(
            "ALTER TABLE `library_files` ADD COLUMN `description` TEXT NOT NULL DEFAULT ''",
            "CREATE TABLE IF NOT EXISTS `file_tags` (`file_id` TEXT NOT NULL, `tag` TEXT NOT NULL, " +
                "PRIMARY KEY(`file_id`, `tag`), FOREIGN KEY(`file_id`) REFERENCES `library_files`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_file_tags_tag` ON `file_tags` (`tag`)",
        )

        /**
         * v5 caches each note's checklist progress on its catalog row (rel031 D). Purely additive: two columns with
         * a zero default, so every existing note is intact and starts with 0/0 until its next save.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                MIGRATION_4_5_STATEMENTS.forEach(connection::execSQL)
            }
        }

        internal val MIGRATION_4_5_STATEMENTS = listOf(
            "ALTER TABLE `notes` ADD COLUMN `checklist_done` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `notes` ADD COLUMN `checklist_total` INTEGER NOT NULL DEFAULT 0",
        )
    }
}

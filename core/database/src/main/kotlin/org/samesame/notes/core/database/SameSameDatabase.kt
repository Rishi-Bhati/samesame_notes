package org.samesame.notes.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import org.samesame.notes.core.database.dao.FolderDao
import org.samesame.notes.core.database.dao.NoteDao
import org.samesame.notes.core.database.dao.SearchDao
import org.samesame.notes.core.database.dao.TagDao
import org.samesame.notes.core.database.entity.FolderClosureEntity
import org.samesame.notes.core.database.entity.FolderEntity
import org.samesame.notes.core.database.entity.NoteEntity
import org.samesame.notes.core.database.entity.NoteSearchEntity
import org.samesame.notes.core.database.entity.NoteTagCrossRef
import org.samesame.notes.core.database.entity.TagEntity

@Database(
    entities = [
        NoteEntity::class,
        FolderEntity::class,
        FolderClosureEntity::class,
        TagEntity::class,
        NoteTagCrossRef::class,
        NoteSearchEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SameSameDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao
    abstract fun tagDao(): TagDao
    abstract fun searchDao(): SearchDao
}

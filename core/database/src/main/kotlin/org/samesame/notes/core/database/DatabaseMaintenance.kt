package org.samesame.notes.core.database

import androidx.room.RoomDatabase
import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps text of locked and deleted notes out of the database file (security review M5 / L5).
 *
 * SQLite does not overwrite freed pages, FTS4 only marks deleted rows until its segments merge, and the WAL keeps
 * old page images until a checkpoint. So: `secure_delete` on every connection (zeroes freed content), an FTS
 * `optimize` (merges the segments, dropping deleted terms) and a WAL checkpoint after a note is locked, and one
 * `VACUUM` for databases written before this existed.
 */
@Singleton
class DatabaseMaintenance @Inject constructor(private val db: SameSameDatabase) {

    /** Turns on `secure_delete` for the writer connection (deletes that follow zero the freed content). */
    suspend fun secureDelete() = db.useWriterConnection { it.execSQL(SECURE_DELETE) }

    /** Drops deleted search text from the FTS index and the WAL. */
    suspend fun purgeDeletedText() = db.useWriterConnection {
        it.execSQL(SECURE_DELETE)
        it.execSQL("INSERT INTO note_search(note_search) VALUES('optimize')")
        it.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
    }

    /** [purgeDeletedText] plus a full `VACUUM` (rewrites the file, so freed pages written before are gone too). */
    suspend fun vacuum() = db.useWriterConnection {
        it.execSQL(SECURE_DELETE)
        it.execSQL("INSERT INTO note_search(note_search) VALUES('optimize')")
        it.execSQL("VACUUM")
        it.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
    }

    /** Applies `secure_delete` to every connection the database opens. */
    object SecureDeleteCallback : RoomDatabase.Callback() {
        override fun onOpen(connection: SQLiteConnection) = connection.execSQL(SECURE_DELETE)
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.query(SECURE_DELETE).close()
        }
    }

    private companion object {
        const val SECURE_DELETE = "PRAGMA secure_delete = ON"
    }
}

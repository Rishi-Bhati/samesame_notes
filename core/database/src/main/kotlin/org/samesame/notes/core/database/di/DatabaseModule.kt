package org.samesame.notes.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import org.samesame.notes.core.database.SameSameDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SameSameDatabase =
        Room.databaseBuilder<SameSameDatabase>(context, "samesame.db")
            // Bundled SQLite: same version and FTS behavior on every device.
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            // Freed pages are zeroed, so text of locked or deleted notes does not linger in the file (review M5).
            .addCallback(org.samesame.notes.core.database.DatabaseMaintenance.SecureDeleteCallback)
            .addMigrations(org.samesame.notes.core.database.SameSameDatabase.MIGRATION_1_2)
            .build()

    @Provides fun noteDao(db: SameSameDatabase) = db.noteDao()
    @Provides fun folderDao(db: SameSameDatabase) = db.folderDao()
    @Provides fun tagDao(db: SameSameDatabase) = db.tagDao()
    @Provides fun searchDao(db: SameSameDatabase) = db.searchDao()
    @Provides fun viewStateDao(db: SameSameDatabase) = db.viewStateDao()
}

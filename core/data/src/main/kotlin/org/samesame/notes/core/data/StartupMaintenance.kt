package org.samesame.notes.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.samesame.notes.core.data.di.ApplicationScope
import org.samesame.notes.core.data.transfer.ShareCache
import org.samesame.notes.core.database.DatabaseMaintenance
import org.samesame.notes.core.document.NoteDocumentStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clean-up at process start (security review L3, L4, M5), off the main thread:
 * - creating the [NoteDocumentStore] deletes decrypted working copies of locked notes left by a killed process;
 * - `cache/share` exports from earlier runs are deleted;
 * - once per install, the database is vacuumed, so text of notes locked before secure_delete existed is gone;
 * - [extra] runs before the vacuum (the app passes its own leftovers, e.g. orphaned voice recordings).
 */
@Singleton
class StartupMaintenance @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: DatabaseMaintenance,
    private val shareCache: ShareCache,
    private val documents: dagger.Lazy<NoteDocumentStore>,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    fun run(extra: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            runCatching { documents.get() }
            runCatching { shareCache.sweep() }
            runCatching(extra)
            runCatching { vacuumOnce(File(File(context.noBackupFilesDir, "maintenance"), VACUUM_MARKER)) }
        }
    }

    private suspend fun vacuumOnce(marker: File) {
        if (marker.exists()) return
        database.vacuum()
        marker.parentFile?.mkdirs()
        marker.createNewFile()
    }

    private companion object {
        const val VACUUM_MARKER = "db-vacuum-v1"
    }
}

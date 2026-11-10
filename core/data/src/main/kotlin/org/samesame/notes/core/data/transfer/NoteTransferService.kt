package org.samesame.notes.core.data.transfer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.export.ExportFormat
import org.samesame.notes.core.export.ExportOptions
import org.samesame.notes.core.model.NoteId
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** One exported note, waiting in the cache directory. */
data class TransferItem(val noteId: NoteId, val name: String, val mimeType: String, val file: File)

/**
 * What an export run produced. [skippedLocked] notes are never exported (locked notes are excluded from sharing and
 * "Save as file"); [failed] notes threw while rendering.
 */
data class TransferBatch(val items: List<TransferItem>, val skippedLocked: Int, val failed: Int)

/** Totals of a bulk export run (see [NoteTransferService.exportEach]). */
data class BulkExportResult(val exported: Int, val skippedLocked: Int, val failed: Int)

/**
 * Exports notes into the app cache so they can be shared or copied to a user-chosen place. Locked notes are skipped.
 * Names are `Title.ext`, made unique within the batch (`Title (1).ext`). Each run replaces the previous run's files.
 */
@Singleton
class NoteTransferService internal constructor(
    private val notes: NoteRepository,
    private val root: File,
) {
    @Inject constructor(notes: NoteRepository, @ApplicationContext context: Context) :
        this(notes, File(context.cacheDir, "share"))

    /** Progress callback gets (index of the note being exported, total, its title). */
    suspend fun exportToCache(
        ids: List<NoteId>,
        format: ExportFormat,
        options: ExportOptions = ExportOptions(),
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): TransferBatch = withContext(Dispatchers.IO) {
        clear()
        val dir = File(root, uniqueRunName("")).also { it.mkdirs() }
        val taken = HashSet<String>()
        val items = ArrayList<TransferItem>()
        var skipped = 0
        var failed = 0
        ids.forEachIndexed { index, id ->
            val note = notes.observeNote(id).first()
            if (note == null) { failed++; return@forEachIndexed }
            if (note.isLocked) { skipped++; return@forEachIndexed }
            val base = TransferNaming.baseName(note)
            onProgress(index, ids.size, base)
            val temp = File(dir, "export-$index.tmp")
            try {
                val target = temp.outputStream().buffered().use { notes.exportNote(id, format, options, it) }
                val name = TransferNaming.uniqueName(base, target.extension, taken)
                val file = File(dir, name)
                check(temp.renameTo(file)) { "rename failed" }
                items += TransferItem(id, name, target.mimeType, file)
            } catch (e: kotlinx.coroutines.CancellationException) {
                temp.delete()
                throw e
            } catch (e: Exception) {
                temp.delete()
                failed++
            }
        }
        onProgress(ids.size, ids.size, "")
        TransferBatch(items, skipped, failed)
    }

    /**
     * Bulk export (Settings > Export notes): exports [ids] one at a time into a scratch file and hands each to
     * [deliver] (which copies it to the user's folder), then deletes it, so a large export never fills the cache.
     * Locked notes are skipped (as for Share / Save as file); a note whose export or delivery throws counts as failed.
     * Names are unique within the run. [onProgress] gets (notes done, total, title of the note being exported).
     */
    suspend fun exportEach(
        ids: List<NoteId>,
        format: ExportFormat,
        options: ExportOptions = ExportOptions(),
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
        deliver: suspend (TransferItem) -> Unit,
    ): BulkExportResult = withContext(Dispatchers.IO) {
        clear()
        val dir = File(root, uniqueRunName("bulk-")).also { it.mkdirs() }
        val taken = HashSet<String>()
        var exported = 0
        var skipped = 0
        var failed = 0
        try {
            ids.forEachIndexed { index, id ->
                val note = notes.observeNote(id).first()
                if (note == null) { failed++; return@forEachIndexed }
                if (note.isLocked) { skipped++; return@forEachIndexed }
                val base = TransferNaming.baseName(note)
                onProgress(index, ids.size, base)
                val temp = File(dir, "export-$index.tmp")
                try {
                    val target = temp.outputStream().buffered().use { notes.exportNote(id, format, options, it) }
                    val name = TransferNaming.uniqueName(base, target.extension, taken)
                    deliver(TransferItem(id, name, target.mimeType, temp))
                    exported++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                } finally {
                    temp.delete()
                }
            }
        } finally {
            dir.deleteRecursively()
        }
        onProgress(ids.size, ids.size, "")
        BulkExportResult(exported, skipped, failed)
    }

    /** Writes every item of [batch] into one zip. */
    suspend fun writeZip(batch: TransferBatch, out: OutputStream) = withContext(Dispatchers.IO) {
        ZipOutputStream(out.buffered()).use { zip ->
            for (item in batch.items) {
                zip.putNextEntry(ZipEntry(item.name))
                item.file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Run folder name: the time plus a counter, so two runs in the same millisecond never share (and wipe) a folder. */
    private fun uniqueRunName(prefix: String) = prefix + System.currentTimeMillis() + "-" + runCounter.incrementAndGet()

    /** Combined size of the library files the [ids] notes link to (for "Include linked files (12 MB)"). */
    suspend fun linkedFilesBytes(ids: List<NoteId>): Long = withContext(Dispatchers.IO) {
        ids.sumOf { runCatching { notes.linkedFilesBytes(it) }.getOrDefault(0L) }
    }

    private val runCounter = java.util.concurrent.atomic.AtomicLong()

    /** Deletes everything this service left in the cache. */
    /** Deletes earlier share/save output (a pending camera capture stays, see [ShareCache]). */
    fun clear() = ShareCache(root).sweep()

    /** Deletes [batch]'s files once they were copied out (Save as file). */
    fun discard(batch: TransferBatch) {
        batch.items.mapNotNull { it.file.parentFile }.distinct().filter { it.parentFile == root }.forEach { it.deleteRecursively() }
    }
}

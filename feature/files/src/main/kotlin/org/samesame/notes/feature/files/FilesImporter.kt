package org.samesame.notes.feature.files

import kotlinx.coroutines.CancellationException
import org.samesame.notes.core.model.files.DuplicatePolicy
import org.samesame.notes.core.model.files.ImportResult
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.core.model.files.LibraryFileTooLargeException
import org.samesame.notes.core.model.files.NotEnoughSpaceException

enum class ImportFailureReason { TooLarge, NoSpace, Unreadable }

data class ImportFailure(val name: String, val reason: ImportFailureReason)

/** What a batch import did. [duplicates] wait for the user's "Keep both / Skip". */
data class ImportReport(
    val imported: List<LibraryFile> = emptyList(),
    val skipped: Int = 0,
    val duplicates: List<ImportResult.Duplicate> = emptyList(),
    val failures: List<ImportFailure> = emptyList(),
    /** Items beyond [FilesImporter.MAX_ITEMS] that were not attempted. */
    val ignored: Int = 0,
)

/** Where a batch import is: item [index] (1-based) of [total], bytes of the current one. */
data class ImportProgress(val index: Int, val total: Int, val name: String, val copied: Long = 0, val totalBytes: Long? = null)

/**
 * Runs a batch of imports one after another. A failed item is reported and the rest continue ("errors are reported per
 * file; the other files continue"); cancelling the coroutine stops at once.
 */
object FilesImporter {
    /** The most items one upload or share takes (the reference's share cap). */
    const val MAX_ITEMS = 100

    suspend fun <T> run(
        items: List<T>,
        nameOf: (T) -> String,
        import: suspend (item: T, policy: DuplicatePolicy, onProgress: (Long, Long?) -> Unit) -> ImportResult,
        policy: DuplicatePolicy = DuplicatePolicy.Ask,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportReport {
        val attempted = items.take(MAX_ITEMS)
        val imported = ArrayList<LibraryFile>()
        val duplicates = ArrayList<ImportResult.Duplicate>()
        val failures = ArrayList<ImportFailure>()
        var skipped = 0
        attempted.forEachIndexed { i, item ->
            val name = nameOf(item)
            onProgress(ImportProgress(i + 1, attempted.size, name))
            try {
                when (val r = import(item, policy) { copied, total -> onProgress(ImportProgress(i + 1, attempted.size, name, copied, total)) }) {
                    is ImportResult.Imported -> imported += r.file
                    is ImportResult.Duplicate -> duplicates += r
                    is ImportResult.Skipped -> skipped++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LibraryFileTooLargeException) {
                failures += ImportFailure(name, ImportFailureReason.TooLarge)
            } catch (e: NotEnoughSpaceException) {
                failures += ImportFailure(name, ImportFailureReason.NoSpace)
            } catch (e: java.io.IOException) {
                failures += ImportFailure(name, ImportFailureReason.Unreadable)
            } catch (e: IllegalArgumentException) {
                failures += ImportFailure(name, ImportFailureReason.Unreadable)
            } catch (e: SecurityException) {
                failures += ImportFailure(name, ImportFailureReason.Unreadable)
            }
        }
        return ImportReport(imported, skipped, duplicates, failures, items.size - attempted.size)
    }
}

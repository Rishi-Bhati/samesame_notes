package org.samesame.notes.feature.files

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.samesame.notes.core.model.files.DuplicatePolicy
import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.ImportResult
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.core.model.files.LibraryFileTooLargeException
import org.samesame.notes.core.model.files.NotEnoughSpaceException
import org.samesame.notes.core.model.files.PendingImport

class FilesImporterTest {
    private fun file(name: String) = LibraryFile(FileId(name), null, name, "", "text/plain", FileCategory.Text, 1, "s", createdAt = 0, modifiedAt = 0)
    private fun pending(name: String) = PendingImport(null, name, "", "text/plain", FileCategory.Text, 1, "s", null)

    @Test fun continuesAfterAFailureAndReportsEachKind() = runTest {
        val report = FilesImporter.run(
            items = listOf("ok", "big", "full", "dup", "skip", "io"),
            nameOf = { it },
            import = { name, _, _ ->
                when (name) {
                    "big" -> throw LibraryFileTooLargeException("big")
                    "full" -> throw NotEnoughSpaceException("full")
                    "io" -> throw java.io.IOException("io")
                    "dup" -> ImportResult.Duplicate(file(name), pending(name))
                    "skip" -> ImportResult.Skipped(file(name))
                    else -> ImportResult.Imported(file(name))
                }
            },
        )
        assertThat(report.imported.map { it.displayName }).containsExactly("ok")
        assertThat(report.duplicates).hasSize(1)
        assertThat(report.skipped).isEqualTo(1)
        assertThat(report.failures.map { it.reason }).containsExactly(ImportFailureReason.TooLarge, ImportFailureReason.NoSpace, ImportFailureReason.Unreadable).inOrder()
    }

    @Test fun capsAtMaxItems() = runTest {
        val items = (1..FilesImporter.MAX_ITEMS + 5).map { "f$it" }
        val seen = ArrayList<String>()
        val report = FilesImporter.run(items, { it }, { n, p, _ -> seen += n; assertThat(p).isEqualTo(DuplicatePolicy.Ask); ImportResult.Imported(file(n)) })
        assertThat(seen).hasSize(FilesImporter.MAX_ITEMS)
        assertThat(report.ignored).isEqualTo(5)
    }

    @Test fun reportsProgressPerItem() = runTest {
        val seen = ArrayList<Int>()
        FilesImporter.run(listOf("a", "b"), { it }, { n, _, _ -> ImportResult.Imported(file(n)) }, onProgress = { seen += it.index })
        assertThat(seen).containsAtLeast(1, 2)
    }
}

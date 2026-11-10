package org.samesame.notes.feature.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.files.FileFolder
import org.samesame.notes.core.model.files.FileFolderId
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class FilesFormatTest {
    @Test fun sizes() {
        assertThat(FilesFormat.size(0, Locale.US)).isEqualTo("0 B")
        assertThat(FilesFormat.size(1023, Locale.US)).isEqualTo("1023 B")
        assertThat(FilesFormat.size(2_516_582, Locale.US)).isEqualTo("2.4 MB")
        assertThat(FilesFormat.size(340L * 1024 * 1024, Locale.US)).isEqualTo("340 MB")
        assertThat(FilesFormat.size(12L * 1024, Locale.US)).isEqualTo("12 KB")
    }

    @Test fun dates() {
        val millis = LocalDate.of(2026, 11, 8).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3_600_000
        assertThat(FilesFormat.date(millis, LocalDate.of(2026, 12, 1), ZoneOffset.UTC, Locale.US)).isEqualTo("8 Nov")
        assertThat(FilesFormat.date(millis, LocalDate.of(2027, 1, 1), ZoneOffset.UTC, Locale.US)).isEqualTo("8 Nov 2026")
    }

    private fun folder(id: String, parent: String?, name: String) =
        FileFolder(FileFolderId(id), parent?.let(::FileFolderId), name, null, 0, 0, 0, 0, 0)

    @Test fun pathJoinsNames() {
        val all = listOf(folder("a", null, "A"), folder("b", "a", "B"))
        assertThat(FilesFormat.path(FileFolderId("b"), all)).isEqualTo("A / B")
        assertThat(FilesFormat.path(null, all)).isEmpty()
    }

    @Test fun chainAndBlockedTargets() {
        val all = listOf(folder("a", null, "A"), folder("b", "a", "B"), folder("c", "b", "C"), folder("d", null, "D"))
        assertThat(FilesViewModel.chain(FileFolderId("c"), all).map { it.name }).containsExactly("A", "B", "C").inOrder()
        assertThat(FilesViewModel.blockedTargets(setOf(FileFolderId("a")), all).map { it.value }).containsExactly("a", "b", "c")
        assertThat(FilesViewModel.blockedTargets(emptySet(), all)).isEmpty()
    }
}

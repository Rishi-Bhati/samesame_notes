package org.samesame.notes.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.data.transfer.BulkExportResult
import org.samesame.notes.core.data.transfer.ImportFailure
import org.samesame.notes.core.data.transfer.ImportSummary
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.feature.settings.SummaryLine.Kind

class BulkTransferTest {
    private val a = NoteId("a"); private val b = NoteId("b"); private val c = NoteId("c")

    @Test fun toggleAndAll() {
        var s = ExportSelection().toggle(b)
        assertThat(s.selected).containsExactly(b)
        assertThat(s.allSelected(listOf(a, b))).isFalse()
        s = s.toggleAll(listOf(a, b))
        assertThat(s.allSelected(listOf(a, b))).isTrue()
        // "All" again clears.
        assertThat(s.toggleAll(listOf(a, b)).selected).isEmpty()
        assertThat(s.toggle(a).selected).containsExactly(b)
    }

    @Test fun allIsNeverTickedForAnEmptyList() {
        assertThat(ExportSelection().allSelected(emptyList())).isFalse()
    }

    @Test fun retainDropsVanishedOrLockedNotesAndOrderFollowsTheList() {
        val s = ExportSelection(setOf(c, a, b)).retain(listOf(b, c))
        assertThat(s.selected).containsExactly(b, c)
        assertThat(ExportSelection(setOf(c, a)).ordered(listOf(a, b, c))).containsExactly(a, c).inOrder()
    }

    @Test fun importSummaryListsSuccessThenNonZeroProblems() {
        val lines = BulkSummary.of(
            ImportSummary(
                listOf(a, b),
                mapOf(ImportFailure.INVALID to 1, ImportFailure.IO to 2, ImportFailure.UNSUPPORTED to 3, ImportFailure.PROTECTED to 1),
            ),
        )
        assertThat(lines).containsExactly(
            SummaryLine(Kind.Imported, 2), SummaryLine(Kind.Unsupported, 3), SummaryLine(Kind.Protected, 1), SummaryLine(Kind.Failed, 3),
        ).inOrder()
        assertThat(BulkSummary.of(ImportSummary(emptyList(), emptyMap()))).containsExactly(SummaryLine(Kind.Imported, 0))
    }

    @Test fun exportSummary() {
        assertThat(BulkSummary.of(BulkExportResult(exported = 4, skippedLocked = 1, failed = 0)))
            .containsExactly(SummaryLine(Kind.Exported, 4), SummaryLine(Kind.SkippedLocked, 1)).inOrder()
        assertThat(BulkSummary.of(BulkExportResult(0, 0, 2))).containsExactly(SummaryLine(Kind.Exported, 0), SummaryLine(Kind.Failed, 2)).inOrder()
    }

    @Test fun documentMimeAvoidsProvidersAppendingAnExtension() {
        assertThat(BulkSummary.documentMime("application/pdf", "application/pdf")).isEqualTo("application/pdf")
        // .ssnote is a zip, but no platform type maps to ".ssnote": octet-stream keeps the name as given.
        assertThat(BulkSummary.documentMime("application/zip", null)).isEqualTo("application/octet-stream")
        assertThat(BulkSummary.documentMime("application/zip", "application/x-foo")).isEqualTo("application/octet-stream")
    }
}

package org.samesame.notes.core.data.transfer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.PageMode

class TransferNamingTest {
    private fun note(title: String, preview: String = "") = Note(
        NoteId("n"), title, preview, null, false, false, PageMode.Pages, 0, 0, 0, 0, null,
    )

    @Test
    fun usesTitleThenFirstPreviewLineThenGenericName() {
        assertThat(TransferNaming.baseName(note("Groceries"))).isEqualTo("Groceries")
        assertThat(TransferNaming.baseName(note("", "\n  Milk and eggs\nmore"))).isEqualTo("Milk and eggs")
        assertThat(TransferNaming.baseName(note("", ""))).isEqualTo("Note")
    }

    @Test
    fun removesCharactersFileSystemsRefuse() {
        assertThat(TransferNaming.baseName(note("a/b:c*d?"))).isEqualTo("a_b_c_d_")
    }

    @Test
    fun duplicateNamesGetNumberedSuffixes() {
        val taken = HashSet<String>()
        assertThat(TransferNaming.uniqueName("Plan", "pdf", taken)).isEqualTo("Plan.pdf")
        assertThat(TransferNaming.uniqueName("Plan", "pdf", taken)).isEqualTo("Plan (1).pdf")
        assertThat(TransferNaming.uniqueName("Plan", "pdf", taken)).isEqualTo("Plan (2).pdf")
        // Other extension and other case are handled independently / case-insensitively.
        assertThat(TransferNaming.uniqueName("Plan", "txt", taken)).isEqualTo("Plan.txt")
        assertThat(TransferNaming.uniqueName("PLAN", "pdf", taken)).isEqualTo("PLAN (3).pdf")
    }

    @Test
    fun commonMimeTypeNarrowsAsFarAsPossible() {
        assertThat(TransferNaming.commonMimeType(listOf("application/pdf", "application/pdf"))).isEqualTo("application/pdf")
        assertThat(TransferNaming.commonMimeType(listOf("image/png", "image/jpeg"))).isEqualTo("image/*")
        assertThat(TransferNaming.commonMimeType(listOf("application/pdf", "text/plain"))).isEqualTo("*/*")
    }
}

package org.samesame.notes.core.model.saveas

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.saveas.SaveOption.ExportDocx
import org.samesame.notes.core.model.saveas.SaveOption.ExportHtml
import org.samesame.notes.core.model.saveas.SaveOption.ExportPdf
import org.samesame.notes.core.model.saveas.SaveOption.SaveAsNote
import org.samesame.notes.core.model.saveas.SaveOption.SaveCopy
import org.samesame.notes.core.model.saveas.SaveOption.SaveInPlace
import org.samesame.notes.core.model.saveas.SaveOption.SaveToFiles
import org.samesame.notes.core.model.saveas.SaveSourceKind.Audio
import org.samesame.notes.core.model.saveas.SaveSourceKind.Image
import org.samesame.notes.core.model.saveas.SaveSourceKind.Markdown
import org.samesame.notes.core.model.saveas.SaveSourceKind.NoteFile
import org.samesame.notes.core.model.saveas.SaveSourceKind.Other
import org.samesame.notes.core.model.saveas.SaveSourceKind.Pdf
import org.samesame.notes.core.model.saveas.SaveSourceKind.Text

class SaveAsOptionsTest {
    @Test fun `markdown is detected by extension first, then by MIME type`() {
        assertThat(SaveAsOptions.isMarkdown("Notes.md", null)).isTrue()
        assertThat(SaveAsOptions.isMarkdown("README.MARKDOWN", "text/plain")).isTrue()
        assertThat(SaveAsOptions.isMarkdown("a.mdown", null)).isTrue()
        assertThat(SaveAsOptions.isMarkdown("plain.txt", "text/markdown")).isFalse()
        assertThat(SaveAsOptions.isMarkdown(null, "text/markdown; charset=utf-8")).isTrue()
        assertThat(SaveAsOptions.isMarkdown("noext", "text/x-markdown")).isTrue()
        assertThat(SaveAsOptions.isMarkdown("noext", "text/plain")).isFalse()
        assertThat(SaveAsOptions.isMarkdown(null, null)).isFalse()
    }

    @Test fun `a foreign text document gets every option, in-place save only when writable`() {
        assertThat(SaveAsOptions.forTextDocument(writable = true, inLibrary = false))
            .containsExactly(SaveInPlace, SaveAsNote, SaveToFiles, SaveCopy, ExportPdf, ExportHtml, ExportDocx).inOrder()
        assertThat(SaveAsOptions.forTextDocument(writable = false, inLibrary = false))
            .containsExactly(SaveAsNote, SaveToFiles, SaveCopy, ExportPdf, ExportHtml, ExportDocx).inOrder()
    }

    @Test fun `a Files entry is not offered Save to Files again`() {
        assertThat(SaveAsOptions.forTextDocument(writable = true, inLibrary = true)).doesNotContain(SaveToFiles)
    }

    @Test fun `received files - single files`() {
        assertThat(SaveAsOptions.forReceived(listOf(Markdown))).containsExactly(SaveToFiles, SaveAsNote, SaveCopy, ExportPdf).inOrder()
        assertThat(SaveAsOptions.forReceived(listOf(Text))).containsExactly(SaveToFiles, SaveAsNote, SaveCopy, ExportPdf).inOrder()
        assertThat(SaveAsOptions.forReceived(listOf(Image))).containsExactly(SaveToFiles, SaveAsNote, SaveCopy, ExportPdf).inOrder()
        assertThat(SaveAsOptions.forReceived(listOf(Pdf))).containsExactly(SaveToFiles, SaveAsNote, SaveCopy).inOrder()
        assertThat(SaveAsOptions.forReceived(listOf(Audio))).containsExactly(SaveToFiles, SaveAsNote, SaveCopy).inOrder()
        assertThat(SaveAsOptions.forReceived(listOf(NoteFile))).containsExactly(SaveToFiles, SaveAsNote, SaveCopy).inOrder()
        assertThat(SaveAsOptions.forReceived(listOf(Other))).containsExactly(SaveToFiles, SaveCopy).inOrder()
    }

    @Test fun `received files - several files`() {
        // Several pictures make one PDF; a copy needs one target, so it is single-file only.
        assertThat(SaveAsOptions.forReceived(listOf(Image, Image))).containsExactly(SaveToFiles, SaveAsNote, ExportPdf).inOrder()
        assertThat(SaveAsOptions.forReceived(listOf(Text, Text))).containsExactly(SaveToFiles, SaveAsNote).inOrder()
        assertThat(SaveAsOptions.forReceived(listOf(Image, Other))).containsExactly(SaveToFiles)
        assertThat(SaveAsOptions.forReceived(emptyList())).isEmpty()
    }

    @Test fun `suggested names replace the extension and never carry a path`() {
        assertThat(SaveAsOptions.suggestedName("Notes.md", "pdf")).isEqualTo("Notes.pdf")
        assertThat(SaveAsOptions.suggestedName("archive.tar.gz", "pdf")).isEqualTo("archive.tar.pdf")
        assertThat(SaveAsOptions.suggestedName("Notes.md")).isEqualTo("Notes.md")
        assertThat(SaveAsOptions.suggestedName("noext", ".DOCX")).isEqualTo("noext.docx")
        assertThat(SaveAsOptions.suggestedName("../../data/secret.txt", "html")).doesNotContain("/")
        assertThat(SaveAsOptions.suggestedName("a\\b\u0000.txt", "pdf")).isEqualTo("a_b.pdf")
        assertThat(SaveAsOptions.suggestedName("..", "pdf")).isEqualTo("Document.pdf")
        assertThat(SaveAsOptions.suggestedName(null, "pdf")).isEqualTo("Document.pdf")
        assertThat(SaveAsOptions.suggestedName("   ", null)).isEqualTo("Document")
        assertThat(SaveAsOptions.suggestedName("x.md", "p/d\\f")).isEqualTo("x.pdf")
    }

    @Test fun `note titles drop the extension`() {
        assertThat(SaveAsOptions.noteTitle("Shopping list.md")).isEqualTo("Shopping list")
        assertThat(SaveAsOptions.noteTitle("a.b.txt")).isEqualTo("a.b")
        assertThat(SaveAsOptions.noteTitle("dir/name.txt")).isEqualTo("dir_name")
        assertThat(SaveAsOptions.noteTitle(null)).isEmpty()
        assertThat(SaveAsOptions.noteTitle("  ")).isEmpty()
    }
}

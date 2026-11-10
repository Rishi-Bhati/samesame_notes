package org.samesame.notes.feature.composer.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.FilePasteMode
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.FileLinks
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.TextRun

class FileLinkPasteTest {
    private fun file(id: String, name: String, mime: String, size: Long = 1000, trashed: Long? = null) = LibraryFile(
        id = FileId(id), folderId = null, displayName = name, ext = name.substringAfterLast('.', ""), mimeType = mime,
        category = if (mime.startsWith("image/")) FileCategory.Image else FileCategory.Pdf, sizeBytes = size, sha256 = "0",
        createdAt = 0, modifiedAt = 0, trashedAt = trashed,
    )

    private val pic = file("00000000-0000-4000-8000-000000000001", "a.png", "image/png")
    private val pdf = file("00000000-0000-4000-8000-000000000002", "b.pdf", "application/pdf")
    private val gone = FileLinks.Target(FileId("00000000-0000-4000-8000-000000000003"), "c.jpg")
    private fun t(f: LibraryFile) = FileLinks.Target(f.id, "old")

    @Test fun `items keep order, drop duplicates and know trashed and missing files`() {
        val trashedPdf = pdf.copy(trashedAt = 5)
        val items = FileLinkPaste.items(listOf(t(pic), gone, t(trashedPdf), t(pic)), mapOf(pic.id to pic, pdf.id to trashedPdf))
        assertThat(items.map { it::class }).containsExactly(
            FileLinkPaste.Item.Live::class, FileLinkPaste.Item.Missing::class, FileLinkPaste.Item.Trashed::class,
        ).inOrder()
        // The current name, not the link's hint; a missing file keeps the hint.
        assertThat(items.map { it.label }).containsExactly("a.png", "c.jpg", "b.pdf").inOrder()
        assertThat(FileLinks.parse(items[1].uri)!!.id).isEqualTo(gone.id)
    }

    @Test fun `only pictures ask, and the setting can skip the question`() {
        val links = FileLinkPaste.items(listOf(t(pdf)), mapOf(pdf.id to pdf))
        val mixed = FileLinkPaste.items(listOf(t(pdf), t(pic)), mapOf(pdf.id to pdf, pic.id to pic))
        assertThat(FileLinkPaste.choice(FilePasteMode.Ask, links)).isEqualTo(FileLinkPaste.Choice.Links)
        assertThat(FileLinkPaste.choice(FilePasteMode.AlwaysShowInNote, links)).isEqualTo(FileLinkPaste.Choice.Links)
        assertThat(FileLinkPaste.choice(FilePasteMode.Ask, mixed)).isEqualTo(FileLinkPaste.Choice.Ask)
        assertThat(FileLinkPaste.choice(FilePasteMode.AlwaysLink, mixed)).isEqualTo(FileLinkPaste.Choice.Links)
        assertThat(FileLinkPaste.choice(FilePasteMode.AlwaysShowInNote, mixed)).isEqualTo(FileLinkPaste.Choice.Show)
        assertThat(FileLinkPaste.showsSeveral(mixed)).isFalse()
    }

    @Test fun `trashed and too big pictures are never shown in place`() {
        assertThat(FileLinkPaste.canShow(FileLinkPaste.Item.Trashed(pic.copy(trashedAt = 1)))).isFalse()
        assertThat(FileLinkPaste.canShow(FileLinkPaste.Item.Live(pic.copy(sizeBytes = Limits.MAX_SHOW_IN_NOTE_BYTES + 1)))).isFalse()
        assertThat(FileLinkPaste.canShow(FileLinkPaste.Item.Live(pic))).isTrue()
    }

    @Test fun `parts show pictures and link everything else`() {
        val items = FileLinkPaste.items(listOf(t(pic), t(pdf)), mapOf(pdf.id to pdf, pic.id to pic))
        assertThat(FileLinkPaste.parts(items, show = true)).containsExactly(
            FileLinkPaste.Part.Picture(pic), FileLinkPaste.Part.Link("b.pdf", FileLinks.format(pdf)),
        ).inOrder()
        assertThat(FileLinkPaste.parts(items, show = false).all { it is FileLinkPaste.Part.Link }).isTrue()
    }

    @Test fun `a link run takes the caret style but its own link`() {
        val rt = FileLinkPaste.linkRun("a.png", "samesame://x", CharStyle(bold = true, link = "other", objectId = "o"))
        assertThat(rt.runs).containsExactly(TextRun(5, CharStyle(bold = true, link = "samesame://x")))
        assertThat(FileLinkPaste.plain(" ", CharStyle(link = "x")).runs.single().style.link).isNull()
    }

    @Test fun `helpers`() {
        assertThat(FileLinkPaste.mediaExtension(file(pic.id.value, "x.jpeg", "image/jpeg"))).isEqualTo("jpg")
        assertThat(FileLinkPaste.mediaExtension(file(pic.id.value, "noext", "image/webp").copy(ext = ""))).isEqualTo("webp")
        assertThat(FileLinkPaste.inlineSize(200, 100, 1000f)).isEqualTo(200f to 100f)
        assertThat(FileLinkPaste.inlineSize(4000, 2000, 1000f)).isEqualTo(1000f to 500f)
        assertThat(FileLinkPaste.looksLikePicture("A.HEIC")).isTrue()
        assertThat(FileLinkPaste.looksLikePicture("a.pdf")).isFalse()
    }
}

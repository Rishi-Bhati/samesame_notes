package org.samesame.notes.core.sdocx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.document.TemplateRef
import org.samesame.notes.core.sdocx.map.SdocxMapper

class SdocxMapperTest {

    private fun read(locked: Boolean = false) =
        SdocxReader().read(SdocxWriter().writeToBytes(SdocxFixtures.archive(locked = locked)))

    @Test
    fun `maps title body pages layout and style`() {
        val archive = read()
        val doc = SdocxMapper.toNoteDocument(archive, noteId = "note-1")

        assertThat(doc.id).isEqualTo("note-1")
        assertThat(doc.title).isEmpty()
        assertThat(doc.body.text).isEqualTo("Hello sdocx\nSecond line")
        assertThat(doc.body.paragraphs).hasSize(2)
        assertThat(doc.body.runs).isNotEmpty()
        doc.body.runs.forEach { run ->
            assertThat(run.style.fontSize).isEqualTo(17f)
            assertThat(run.style.color).isEqualTo(0xFF252525)
        }

        assertThat(doc.pages).hasSize(1)
        assertThat(doc.pages.single().id).isEqualTo(SdocxFixtures.UUID_1)
        // 0xFFFCFCFC is the the reference default base_background_color_white, normalized to null
        assertThat(doc.pages.single().backgroundColor).isNull()
        assertThat(doc.pages.single().template).isEqualTo(TemplateRef.Builtin(org.samesame.notes.core.model.PageTemplate.Blank))

        // pageMode 0 (list) is still a fixed-page document: list only describes scrolling.
        assertThat(doc.layout.mode).isEqualTo(PageMode.Pages)
        assertThat(doc.style.adjustForDarkMode).isTrue()
        assertThat(SdocxMapper.toImportBundle(archive, "note-1").viewState?.pageIndex).isEqualTo(0)
    }

    @Test
    fun `list and single page modes both map to fixed pages, never an infinite canvas`() {
        val list = SdocxReader().read(SdocxWriter().writeToBytes(SdocxFixtures.archive()))
        val single = SdocxReader().read(
            SdocxWriter().writeToBytes(SdocxFixtures.archive(pageMode = SdocxFormat.PageMode.SINGLE)),
        )

        assertThat(SdocxMapper.toNoteDocument(list, "n").layout.mode).isEqualTo(PageMode.Pages)
        assertThat(SdocxMapper.toNoteDocument(single, "n").layout.mode).isEqualTo(PageMode.Pages)
        assertThat(SdocxMapper.toNoteDocument(list, "n").pages).hasSize(1)
        assertThat(SdocxMapper.toNoteDocument(single, "n").pages).hasSize(1)
    }

    @Test
    fun `locked flag maps to a lock that carries the owner id`() {
        val archive = read(locked = true)
        val lock = SdocxMapper.lockOf(archive)
        assertThat(lock.locked).isTrue()
        assertThat(lock.ownerId).isEqualTo(SdocxFixtures.ACCOUNT_ID)
        // The body is still mapped (not encrypted).
        assertThat(SdocxMapper.toNoteDocument(archive, "n").body.text).isNotEmpty()
    }

    @Test
    fun `geometry scales from the sdocx page width to samesame page units`() {
        val archive = read()
        // 1080 pu / 2000 px = 0.54.
        assertThat(SdocxMapper.geometryScale(archive)).isWithin(1e-5f).of(0.54f)
    }

    @Test
    fun `applyDocumentEdits overlays title and body and keeps pages objects`() {
        val archive = read()
        val document = SdocxMapper.toNoteDocument(archive, "note-1").copy(
            title = "Edited title",
            body = org.samesame.notes.core.model.richtext.RichText.of("Edited body"),
        )

        val edited = SdocxMapper.applyDocumentEdits(archive, document, nowUs = SdocxFixtures.MODIFIED_US)
        val restored = SdocxReader().read(SdocxWriter().writeToBytes(edited))

        assertThat(SdocxMapper.title(restored)).isEqualTo("Edited title")
        assertThat(restored.note.body.text?.text).isEqualTo("Edited body")
        // The imported sdocx page mode survives an edit-export round trip.
        assertThat(restored.endTag.pageMode).isEqualTo(archive.endTag.pageMode)
        assertThat(restored.pages).hasSize(archive.pages.size)
        assertThat(restored.pages.single().layers.single().objects).hasSize(
            archive.pages.single().layers.single().objects.size,
        )
    }

    @Test
    fun `applyDocumentEdits returns the same archive when text is unchanged`() {
        val archive = read()
        val document = SdocxMapper.toNoteDocument(archive, "note-1")

        assertThat(SdocxMapper.applyDocumentEdits(archive, document, nowUs = SdocxFixtures.MODIFIED_US))
            .isSameInstanceAs(archive)
    }

    @Test
    fun `missing body text is an unchanged empty document`() {
        val source = read()
        val note = source.note
        val withoutBody = org.samesame.notes.core.sdocx.record.NoteRecord(
            note.propOffset, note.noteFlags, note.propMask, note.formatVersion, note.noteId,
            note.fileRevision, note.createdTime, note.modifiedTime, note.width, note.height,
            note.pageHorizontalPadding, note.pageVerticalPadding, note.minFormatVersion, note.title,
            org.samesame.notes.core.sdocx.record.TextBoxRecord(note.body.raw, null), note.properties, note.docHash,
        )
        val archive = SdocxArchive(source.endTag, withoutBody, source.pageIdInfo, source.pages,
            source.media, source.mediaFiles, source.extraEntries)
        val document = SdocxMapper.toNoteDocument(archive, "note-1")
        assertThat(document.body.text).isEmpty()
        assertThat(SdocxMapper.applyDocumentEdits(archive, document, nowUs = 1L)).isSameInstanceAs(archive)
    }
}

package org.samesame.notes.core.sdocx

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.sdocx.map.SdocxMapper
import java.io.File

/**
 * Test-only checks against a local `.sdocx` fixture directory given by `SAMESAME_SDOCX_FIXTURE_DIR`.
 *
 * The fixture is not part of the repo. The test is skipped when the variable is not set.
 */
class SdocxRealFixtureTest {

    private fun researchFixture(name: String): ByteArray? {
        val dir = System.getenv("SAMESAME_SDOCX_FIXTURE_DIR") ?: return null
        val candidate = File(dir, name)
        return if (candidate.isFile) candidate.readBytes() else null
    }

    @Test
    fun `coedit_empty fixture maps to its two page records with body Aa`() {
        val bytes = researchFixture("coedit_empty.sdocx")
        assumeNotNull(bytes)

        val archive = SdocxReader().read(bytes!!)
        assertThat(archive.pages.map { it.pageId }).containsExactly(
            "de1ccfaa-dd73-11ec-885b-43ddd3a45552",
            "de1d5f06-dd73-11ec-b04e-43633b95ceea",
        ).inOrder()

        val bundle = SdocxMapper.toImportBundle(archive, noteId = "fixture-note")
        val doc = bundle.document
        assertThat(bundle.title).isEmpty()
        assertThat(doc.title).isEmpty()
        assertThat(doc.body.text).isEqualTo("Aa")
        assertThat(doc.title).isNotEqualTo(doc.body.text)
        // Fixed pages, so the composer cannot auto-grow the imported note's infinite canvas.
        assertThat(doc.layout.mode).isEqualTo(PageMode.Pages)
        assertThat(doc.pages.map { it.id }).containsExactlyElementsIn(archive.pages.map { it.pageId }).inOrder()
        assertThat(doc.pages).hasSize(2)
        assertThat(doc.pages.map { it.id }.toSet()).hasSize(doc.pages.size)

        // What the repository writes to document.json must not grow the page list either.
        val persisted = org.samesame.notes.core.model.document.NoteDocument.fromJson(doc.toJson())
        assertThat(persisted.pages.map { it.id }).containsExactlyElementsIn(doc.pages.map { it.id }).inOrder()
        assertThat(persisted.body.text).isEqualTo("Aa")
        assertThat(persisted.title).isEmpty()
    }

    @Test
    fun `voice fixture imports correctly if present`() {
        val file = File(System.getenv("SAMESAME_SDOCX_VOICE_FIXTURE") ?: "/nonexistent/voice-fixture.sdocx")
        assumeTrue("Optional owner-approved voice fixture is unavailable", file.isFile)

        val bytes = file.readBytes()
        val archive = SdocxReader().read(bytes)

        val bundle = SdocxMapper.toImportBundle(archive, noteId = "voice-fixture-note")
        val doc = bundle.document
        assertThat(doc.title).isEqualTo("22 Nov")
        assertThat(doc.pages).hasSize(2)
        // Default the reference white background 0xFFFCFCFC is normalized to null (default)
        assertThat(doc.style.backgroundColor).isNull()
        assertThat(doc.pages[0].backgroundColor).isNull()

        // Voice recordings from note.properties.voiceData map to the note-level recording list;
        // the imported pages stay blank (no synthesized chips).
        val recordings = doc.recordings
        assertThat(recordings).hasSize(2)
        assertThat(recordings[0].durationMs).isEqualTo(18_000L)
        assertThat(recordings[1].durationMs).isEqualTo(1_492_000L)
        recordings.forEach { recording -> assertThat(bundle.media[recording.media]).isNotEmpty() }

        val sideChannel = SdocxSideChannel.encode(archive, bytes)
        assertThat(sideChannel.getValue(SdocxSideChannel.RAW_ARCHIVE_PATH)).isEqualTo(bytes)
        val restored = SdocxSideChannel.decode(sideChannel)!!
        assertThat(SdocxMapper.applyDocumentEdits(restored, doc, nowUs = 1L)).isSameInstanceAs(restored)
    }
}

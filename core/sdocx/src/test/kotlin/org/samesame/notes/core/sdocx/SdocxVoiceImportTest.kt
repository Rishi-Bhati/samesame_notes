package org.samesame.notes.core.sdocx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.VoiceTimelineEvent
import org.samesame.notes.core.sdocx.map.SdocxMapper
import org.samesame.notes.core.sdocx.record.VoiceDataRecord

class SdocxVoiceImportTest {
    private val voices = listOf(
        VoiceDataRecord(7, "First", "00:18", 123L, listOf(VoiceDataRecord.Event(1, 0L), VoiceDataRecord.Event(4, 999L)), 18_000L),
        VoiceDataRecord(42, "Second", "24:52", 456L, emptyList(), null),
    )

    private fun archive(): SdocxArchive = SdocxReader().read(
        SdocxWriter().writeToBytes(SdocxFixtures.archive(bodyText = "", voices = voices)),
    )

    @Test fun `note recordings map to the note-level list in recording order via bind numbers`() {
        val source = archive()
        val bundle = SdocxMapper.toImportBundle(source, "imported")
        val recordings = bundle.document.recordings

        assertThat(bundle.document.body.text).isEmpty()
        assertThat(recordings.map { it.media }).containsExactly("media/voice-7.m4a", "media/voice-42.m4a").inOrder()
        assertThat(recordings.map { it.durationMs }).containsExactly(18_000L, 1_492_000L).inOrder()
        assertThat(recordings.map { it.name }).containsExactly("First", "Second").inOrder()
        assertThat(recordings.map { it.createdAtMs }).containsExactly(123L, 456L).inOrder()
        assertThat(recordings.map { it.displayDuration }).containsExactly("00:18", "24:52").inOrder()
        assertThat(recordings.map { it.sourceFileId }).containsExactly(7, 42).inOrder()
        assertThat(recordings.map { it.id }.toSet()).hasSize(2)
        assertThat(SdocxMapper.mapRecordings(source)).isEqualTo(recordings)
    }

    @Test fun `timeline events map to note recordings`() {
        val recordings = SdocxMapper.mapRecordings(archive())
        assertThat(recordings[0].timeline).containsExactly(VoiceTimelineEvent(1, 0L), VoiceTimelineEvent(4, 999L)).inOrder()
        assertThat(recordings[1].timeline).isEmpty()
    }

    @Test fun `recordings survive json round trip of the document`() {
        val doc = SdocxMapper.toNoteDocument(archive(), "imported")
        val reopened = NoteDocument.fromJson(doc.toJson())
        assertThat(reopened.recordings).isEqualTo(doc.recordings)
    }

    @Test fun `missing attachments do not produce unusable players and remain in raw properties`() {
        val source = archive()
        val missing = SdocxArchive(source.endTag, source.note, source.pageIdInfo, source.pages, source.media,
            source.mediaFiles - "media/voice-7.m4a", source.extraEntries)
        val mapped = SdocxMapper.mapRecordings(missing)
        assertThat(mapped.map { it.media }).containsExactly("media/voice-42.m4a")
        assertThat(missing.note.properties.raw).isEqualTo(source.note.properties.raw)
    }

    @Test fun `media paths remap for recordings`() {
        val source = archive()
        val bundle = SdocxMapper.toImportBundle(source, "imported")
        val stored = mapOf("media/voice-7.m4a" to "media/hash1.m4a", "media/voice-42.m4a" to "media/hash2.m4a")
        val mapped = SdocxMapper.withMediaPaths(bundle.document, stored)
        val reopened = NoteDocument.fromJson(mapped.toJson())
        assertThat(reopened.recordings.map { it.media }).containsExactly("media/hash1.m4a", "media/hash2.m4a").inOrder()
        assertThat(reopened.recordings.map { it.durationMs }).containsExactly(18_000L, 1_492_000L).inOrder()
    }

    @Test fun `duration supports seconds minutes hours and rejects malformed or overflowing values`() {
        mapOf("18" to 18_000L, " 00:18 " to 18_000L, "24:52" to 1_492_000L,
            "01:02:03" to 3_723_000L, "00:00" to 0L).forEach { (text, expected) ->
            assertThat(SdocxMapper.parsePlayTimeMs(text)).isEqualTo(expected)
        }
        listOf(null, "", "-1", "bad:18", "1::2", "1:60", "1:60:00", "1:2:3:4",
            "9223372036854775807", "99999999999999999999999").forEach {
            assertThat(SdocxMapper.parsePlayTimeMs(it)).isEqualTo(0L)
        }
    }

    @Test fun `title edit preserves unedited body voice metadata and attachments`() {
        val source = archive()
        val edited = SdocxMapper.applyDocumentEdits(source,
            SdocxMapper.toNoteDocument(source, "imported").copy(title = "Changed"), 900L)
        assertThat(edited.note.body).isSameInstanceAs(source.note.body)
        assertThat(edited.note.properties.raw).isEqualTo(source.note.properties.raw)
        assertThat(edited.mediaFiles).isEqualTo(source.mediaFiles)
        val restored = SdocxReader().read(SdocxWriter().writeToBytes(edited))
        assertThat(restored.note.properties.voiceData!!.map { it.fileId }).containsExactly(7, 42).inOrder()
    }

    @Test fun `elapsed recording time wins and unspecified falls back to the display text`() {
        val recordings = SdocxMapper.mapRecordings(archive())
        assertThat(recordings[0].elapsedMs).isEqualTo(18_000L)
        assertThat(recordings[0].durationMs).isEqualTo(18_000L)
        assertThat(recordings[1].elapsedMs).isEqualTo(0L)
        assertThat(recordings[1].durationMs).isEqualTo(1_492_000L)
    }
}

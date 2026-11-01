package org.samesame.notes.core.sdocx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.NoteRecording
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.VoiceTimelineEvent
import org.samesame.notes.core.sdocx.map.SdocxMapper
import org.samesame.notes.core.sdocx.record.VoiceDataRecord

class SdocxVoiceExportTest {
    private val audio1 = ByteArray(300) { (it * 7).toByte() }
    private val audio2 = ByteArray(120) { (it * 3 + 1).toByte() }
    private val t0 = 1_760_000_000_000L // wall-clock ms

    private val recs = listOf(
        NoteRecording(
            id = "r1", media = "media/aaa.m4a", name = "Lecture", createdAtMs = t0, elapsedMs = 18_000L,
            displayDuration = "00:18",
            timeline = listOf(
                VoiceTimelineEvent(1, t0), VoiceTimelineEvent(2, t0 + 5_000), VoiceTimelineEvent(3, t0 + 6_000),
                VoiceTimelineEvent(4, t0 + 20_000),
            ),
        ),
        // No stored timeline, no display text: synthesized.
        NoteRecording(id = "r2", media = "media/bbb.m4a", name = "Second", createdAtMs = t0 + 100_000, elapsedMs = 75_000L),
    )
    private val audio = mapOf("r1" to audio1, "r2" to audio2)

    private val doc = NoteDocument(id = "n1", title = "T", pages = listOf(PageSpec(id = "p1")), recordings = recs)

    private fun nativeBytes(): ByteArray {
        val base = SdocxDocumentBuilder.toArchive(doc, SdocxFixtures.MODIFIED_US) { SdocxFixtures.UUID_1 }
        return SdocxWriter().writeToBytes(
            SdocxVoiceList.apply(base, doc.recordings, SdocxFixtures.MODIFIED_US) { audio[it.id] },
        )
    }

    @Test fun `native note recordings round trip through our reader and mapper`() {
        val read = SdocxReader().read(nativeBytes())
        assertThat(read.note.propMask and 0x2000).isNotEqualTo(0)
        assertThat(read.pages.flatMap { it.layers }.flatMap { it.objects }).isEmpty()
        val back = SdocxMapper.mapRecordings(read)

        assertThat(back.map { it.name }).containsExactly("Lecture", "Second").inOrder()
        assertThat(back.map { it.elapsedMs }).containsExactly(18_000L, 75_000L).inOrder()
        assertThat(back.map { it.createdAtMs }).containsExactly(t0, t0 + 100_000).inOrder()
        assertThat(back[0].displayDuration).isEqualTo("00:18")
        assertThat(back[1].displayDuration).isEqualTo("01:15")
        assertThat(back[0].timeline).isEqualTo(recs[0].timeline)
        assertThat(back[1].timeline).containsExactly(
            VoiceTimelineEvent(1, t0 + 100_000), VoiceTimelineEvent(4, t0 + 175_000),
        ).inOrder()
        assertThat(read.mediaFiles[back[0].media]).isEqualTo(audio1)
        assertThat(read.mediaFiles[back[1].media]).isEqualTo(audio2)
        val binds = read.media.entries.map { it.bindNumber }
        assertThat(binds).containsNoDuplicates()
        assertThat(back.map { it.sourceFileId }).containsExactlyElementsIn(binds)
        assertThat(read.media.entries.all { it.fileHash.length == 64 && it.refCount == 1 }).isTrue()
    }

    @Test fun `native export without recordings has no voice section`() {
        val base = SdocxDocumentBuilder.toArchive(doc.copy(recordings = emptyList()), 1L) { SdocxFixtures.UUID_1 }
        assertThat(SdocxVoiceList.apply(base, emptyList(), 2L) { null }).isSameInstanceAs(base)
    }

    private val voices = listOf(
        VoiceDataRecord(7, "First", "00:18", 123L, listOf(VoiceDataRecord.Event(1, 0L), VoiceDataRecord.Event(4, 999L)), 18_000L, byteArrayOf(9, 9)),
        VoiceDataRecord(42, "Second", "24:52", 456L, emptyList(), null),
    )

    private fun imported(): SdocxArchive = SdocxReader().read(
        SdocxWriter().writeToBytes(SdocxFixtures.archive(bodyText = "", voices = voices)),
    )

    @Test fun `unchanged imported recordings leave the archive untouched`() {
        val src = imported()
        val mapped = SdocxMapper.mapRecordings(src)
        assertThat(SdocxVoiceList.apply(src, mapped, 5L) { null }).isSameInstanceAs(src)
    }

    @Test fun `renamed delete and added recordings merge into the voice list`() {
        val src = imported()
        val mapped = SdocxMapper.mapRecordings(src)
        val added = recs[0].copy(id = "new1")
        val edited = listOf(mapped[1].copy(name = "Renamed"), added)
        val out = SdocxReader().read(
            SdocxWriter().writeToBytes(SdocxVoiceList.apply(src, edited, 9L) { if (it.id == "new1") audio1 else null }),
        )
        val list = out.note.properties.voiceData!!
        assertThat(list.map { it.name }).containsExactly("Renamed", "Lecture").inOrder()
        assertThat(list[0].fileId).isEqualTo(42)
        assertThat(list[0].createdTime).isEqualTo(456L)
        assertThat(list[0].playTime).isEqualTo("24:52")
        assertThat(list[1].fileId).isGreaterThan(42)
        assertThat(out.mediaFiles["media/" + out.mediaByBindNumber[list[1].fileId]!!.fileName]).isEqualTo(audio1)

        // Renaming the first one keeps its unknown trailing bytes and recordingTime.
        val renamed = SdocxReader().read(
            SdocxWriter().writeToBytes(SdocxVoiceList.apply(src, listOf(mapped[0].copy(name = "X"), mapped[1]), 9L) { null }),
        ).note.properties.voiceData!!
        assertThat(renamed[0].name).isEqualTo("X")
        assertThat(renamed[0].recordingTime).isEqualTo(18_000L)
        assertThat(renamed[0].extra).isEqualTo(byteArrayOf(9, 9))
        assertThat(renamed[1].name).isEqualTo("Second")
    }

    @Test fun `deleting all recordings clears the voice bit`() {
        val out = SdocxReader().read(SdocxWriter().writeToBytes(SdocxVoiceList.apply(imported(), emptyList(), 9L) { null }))
        assertThat(out.note.propMask and 0x2000).isEqualTo(0)
        assertThat(out.note.properties.voiceData).isNull()
    }
}

package org.samesame.notes.core.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.document.NoteDocumentStore
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.NoteRecording
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.sdocx.SdocxDocumentBuilder
import org.samesame.notes.core.sdocx.SdocxReader
import org.samesame.notes.core.sdocx.SdocxSideChannel
import org.samesame.notes.core.sdocx.SdocxWriter
import org.samesame.notes.core.sdocx.map.SdocxMapper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Exercises the same persistence helper used by DefaultNoteRepository, without Room or audio playback. */
class SdocxPersistenceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `recordings and playable files survive import flush and reopen`() = runBlocking {
        val archive = SdocxDocumentBuilder.toArchive(NoteDocument(id = "source", pages = listOf(PageSpec("page-1"))), 1L)
        val audio = mapOf("media/first.m4a" to byteArrayOf(10, 11, 12), "media/second.m4a" to byteArrayOf(20, 21, 22))
        val recordings = audio.keys.mapIndexed { index, path ->
            NoteRecording(
                id = "voice-$index",
                media = path,
                name = "Recording ${index + 1}",
                createdAtMs = 1_700_000_000_000L + index,
                elapsedMs = listOf(18_000L, 1_492_000L)[index],
                displayDuration = listOf("00:18", "24:52")[index],
            )
        }
        val document = SdocxMapper.toNoteDocument(archive, "imported").copy(recordings = recordings)
        val bundle = SdocxMapper.ImportBundle(document, document.title, SdocxMapper.lockOf(archive), audio, emptyMap())
        val bytes = externalZipMetadata(SdocxWriter().writeToBytes(archive))
        verifyPersistence(bundle, SdocxReader().read(bytes), bytes)
    }

    @Test fun `approved real voice archive survives persistence and unchanged export byte for byte`() = runBlocking {
        val fixture = File(System.getenv("SAMESAME_SDOCX_VOICE_FIXTURE") ?: "/nonexistent/voice-fixture.sdocx")
        assumeTrue("Optional owner-approved voice fixture is unavailable", fixture.isFile)
        val bytes = fixture.readBytes()
        val archive = SdocxReader().read(bytes)
        val bundle = SdocxMapper.toImportBundle(archive, "imported")
        assertThat(bundle.document.recordings).hasSize(2)
        verifyPersistence(bundle, archive, bytes)
    }

    @Test fun `renamed deleted and added recordings of an imported note reach the exported voice list`() = runBlocking {
        val t0 = 1_760_000_000_000L
        val seed = listOf("a", "b").mapIndexed { i, n ->
            NoteRecording(id = n, media = "media/$n.m4a", name = "Voice $n", createdAtMs = t0 + i, elapsedMs = 10_000L + i)
        }
        val seedAudio = mapOf("a" to byteArrayOf(1, 2), "b" to byteArrayOf(3, 4))
        val base = SdocxDocumentBuilder.toArchive(NoteDocument(id = "src", pages = listOf(PageSpec("page-1"))), 1L)
        val withVoice = org.samesame.notes.core.sdocx.SdocxVoiceList.apply(base, seed, 2L) { seedAudio[it.id] }
        val bytes = SdocxWriter().writeToBytes(withVoice)
        val archive = SdocxReader().read(bytes)
        val bundle = SdocxMapper.toImportBundle(archive, "imported")
        val store = NoteDocumentStore(tmp.newFolder("notes"), tmp.newFolder("working"))
        val id = bundle.document.id
        store.writeSdocxImport(bundle, archive, bytes)
        store.flush(id, close = true)
        val document = store.readDocument(id)
        assertThat(document.recordings.map { it.name }).containsExactly("Voice a", "Voice b").inOrder()
        val extras = store.readExtras(id)
        val original = SdocxSideChannel.decode(extras)!!
        val raw = extras[SdocxSideChannel.RAW_ARCHIVE_PATH]

        val unchanged = ByteArrayOutputStream()
        SdocxExport.exportImported(original, document, unchanged, raw)
        assertThat(unchanged.toByteArray()).isEqualTo(bytes)

        val newRec = NoteRecording(id = "n", media = "media/n.m4a", name = "Added", createdAtMs = t0 + 9, elapsedMs = 5_000L)
        val edited = document.copy(recordings = listOf(document.recordings[1].copy(name = "Renamed"), newRec))
        val out = ByteArrayOutputStream()
        SdocxExport.exportImported(original, edited, out, raw, mapOf("n" to byteArrayOf(7, 7, 7)))
        val reread = SdocxReader().read(out.toByteArray())
        val mapped = SdocxMapper.mapRecordings(reread)
        assertThat(mapped.map { it.name }).containsExactly("Renamed", "Added").inOrder()
        assertThat(reread.mediaFiles[mapped[0].media]).isEqualTo(byteArrayOf(3, 4))
        assertThat(reread.mediaFiles[mapped[1].media]).isEqualTo(byteArrayOf(7, 7, 7))
        assertThat(mapped[0].createdAtMs).isEqualTo(t0 + 1)
    }

    private suspend fun verifyPersistence(
        bundle: SdocxMapper.ImportBundle,
        archive: org.samesame.notes.core.sdocx.SdocxArchive,
        bytes: ByteArray,
    ) {
        val store = NoteDocumentStore(tmp.newFolder("notes"), tmp.newFolder("working"))
        val id = bundle.document.id
        store.writeSdocxImport(bundle, archive, bytes)
        store.flush(id, close = true)
        val document = store.readDocument(id)
        assertThat(document.pages).hasSize(bundle.document.pages.size)
        val expected = bundle.document.recordings
        val restored = document.recordings
        assertThat(restored.map { it.id }).containsExactlyElementsIn(expected.map { it.id }).inOrder()
        assertThat(restored.map { it.durationMs }).containsExactlyElementsIn(expected.map { it.durationMs }).inOrder()
        assertThat(restored.map { it.name }).containsExactlyElementsIn(expected.map { it.name }).inOrder()
        assertThat(restored.map { it.createdAtMs }).containsExactlyElementsIn(expected.map { it.createdAtMs }).inOrder()
        assertThat(restored.map { it.timeline }).containsExactlyElementsIn(expected.map { it.timeline }).inOrder()
        for ((before, after) in expected.zip(restored)) {
            assertThat(after.media).startsWith("media/")
            assertThat(after.media).endsWith(".m4a")
            assertThat(after.media).isNotEqualTo(before.media)
            // mediaFile is the repository API used by the composer's voice player.
            assertThat(store.mediaFile(id, after.media)!!.readBytes()).isEqualTo(bundle.media.getValue(before.media))
        }
        val extras = store.readExtras(id)
        val original = SdocxSideChannel.decode(extras)!!
        val output = ByteArrayOutputStream()
        SdocxExport.exportImported(original, document, output, extras[SdocxSideChannel.RAW_ARCHIVE_PATH])
        assertThat(output.toByteArray()).isEqualTo(bytes)

        val changed = ByteArrayOutputStream()
        SdocxExport.exportImported(original, document.copy(title = "Edited"), changed,
            extras[SdocxSideChannel.RAW_ARCHIVE_PATH])
        val edited = SdocxReader().read(changed.toByteArray())
        assertThat(SdocxMapper.title(edited)).isEqualTo("Edited")
        assertThat(edited.note.body.raw).isEqualTo(archive.note.body.raw)
        assertThat(edited.note.properties.raw).isEqualTo(archive.note.properties.raw)
        assertThat(edited.mediaFiles.keys).containsExactlyElementsIn(archive.mediaFiles.keys)
        archive.mediaFiles.forEach { (path, media) -> assertThat(edited.mediaFiles[path]).isEqualTo(media) }
    }

    private fun externalZipMetadata(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.setComment("Synthetic producer metadata must survive unchanged export")
            ZipInputStream(bytes.inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    zip.putNextEntry(ZipEntry(entry.name).apply { time = 1_700_000_000_000L })
                    input.copyTo(zip)
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }
}

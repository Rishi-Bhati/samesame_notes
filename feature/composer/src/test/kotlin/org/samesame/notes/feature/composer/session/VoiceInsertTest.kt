package org.samesame.notes.feature.composer.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.feature.composer.FakePrefs
import org.samesame.notes.feature.composer.FakeStorage

/** A finished recording becomes one note-level recording: one undo step, like the reference's insert. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInsertTest {
    private val storage = FakeStorage()

    private fun TestScope.session() = EditorSession(
        noteId = "n1",
        initialDocument = NoteDocument(id = "n1", pages = listOf(PageSpec("p1"))),
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        pageLoader = storage::load,
        saveSink = storage::save,
        packContainer = storage::pack,
        mediaLoader = { null },
        mediaSink = { _, ext -> "media/new.$ext" },
        prefs = FakePrefs(),
        clipboard = EditorClipboard(),
        clock = { testScheduler.currentTime },
    )

    @Test fun addVoiceRecordingIsOneUndoStep() = runTest {
        val s = session()
        runCurrent()
        val id = s.addVoiceRecording("media/abc.m4a", name = "First", createdAtMs = 1_700_000_000_000L, elapsedMs = 12_000)
        assertThat(id).isNotEmpty()
        val r = s.voiceRecordings().single()
        assertThat(r.media).isEqualTo("media/abc.m4a")
        assertThat(r.durationMs).isEqualTo(12_000)
        assertThat(r.name).isEqualTo("First")

        assertThat(s.undo()).isTrue()
        assertThat(s.voiceRecordings()).isEmpty()
        assertThat(s.undo()).isFalse()
        assertThat(s.redo()).isTrue()
        assertThat(s.voiceRecordings().single().id).isEqualTo(id)
    }

    @Test fun twoRecordingsAreTwoListEntriesWithTheirOwnUndoSteps() = runTest {
        val s = session()
        runCurrent()
        s.addVoiceRecording("media/a.m4a", elapsedMs = 1_000)
        s.addVoiceRecording("media/b.m4a", elapsedMs = 2_000)
        assertThat(s.voiceRecordings().map { it.media }).containsExactly("media/a.m4a", "media/b.m4a").inOrder()
        s.undo()
        assertThat(s.voiceRecordings().map { it.media }).containsExactly("media/a.m4a")
    }

    @Test fun unnamedRecordingsGetVoiceNumbersAtCreation() = runTest {
        val s = session()
        runCurrent()
        s.addVoiceRecording("media/a.m4a", elapsedMs = 1_000)
        s.addVoiceRecording("media/b.m4a", elapsedMs = 1_000)
        assertThat(s.voiceRecordings().map { it.name }).containsExactly("Voice 001", "Voice 002").inOrder()
    }

    @Test fun renameVoiceRecordingChangesTheStoredName() = runTest {
        val s = session()
        runCurrent()
        val id = s.addVoiceRecording("media/a.m4a", elapsedMs = 1_000)
        s.renameVoiceRecording(id, "Meeting notes")
        assertThat(s.voiceRecordings().single().name).isEqualTo("Meeting notes")
    }

    @Test fun deleteVoiceRecordingsRemovesTheSelectionAsOneUndoStep() = runTest {
        val s = session()
        runCurrent()
        val a = s.addVoiceRecording("media/a.m4a", elapsedMs = 1_000)
        val b = s.addVoiceRecording("media/b.m4a", elapsedMs = 2_000)
        val c = s.addVoiceRecording("media/c.m4a", elapsedMs = 3_000)
        s.deleteVoiceRecordings(listOf(a, c))
        assertThat(s.voiceRecordings().map { it.id }).containsExactly(b)
        s.undo()
        assertThat(s.voiceRecordings().map { it.id }).containsExactly(a, b, c)
    }

    /** Round-7 A8: deleting the last recording hides the docked bar; the editor toolbar Undo must bring it all back. */
    @Test fun deletingTheLastRecordingIsUndoableAndKeepsItsMedia() = runTest {
        val s = session()
        runCurrent()
        val id = s.addVoiceRecording("media/only.m4a", name = "Voice 001", elapsedMs = 9_000)
        s.deleteVoiceRecordings(listOf(id))
        assertThat(s.voiceRecordings()).isEmpty() // the whole docked bar hides
        assertThat(s.undo()).isTrue()
        val restored = s.voiceRecordings().single()
        assertThat(restored.id).isEqualTo(id)
        assertThat(restored.media).isEqualTo("media/only.m4a") // media kept, so undo is possible
        assertThat(restored.name).isEqualTo("Voice 001")
        assertThat(restored.durationMs).isEqualTo(9_000)
        assertThat(s.redo()).isTrue()
        assertThat(s.voiceRecordings()).isEmpty()
    }

    @Test fun storeMediaGoesThroughTheBundleSink() = runTest {
        val s = session()
        runCurrent()
        assertThat(s.storeMedia(ByteArray(3), "m4a")).isEqualTo("media/new.m4a")
    }
}

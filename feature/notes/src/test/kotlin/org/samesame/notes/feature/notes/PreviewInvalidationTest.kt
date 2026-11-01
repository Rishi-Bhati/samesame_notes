package org.samesame.notes.feature.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.PageMode

class PreviewInvalidationTest {
    private fun note(id: String = "n", locked: Boolean = false, modifiedAt: Long = 10L) = Note(
        id = NoteId(id),
        title = "",
        previewText = "",
        folderId = null,
        isFavorite = false,
        isLocked = locked,
        pageMode = PageMode.Pages,
        backgroundColor = 0,
        createdAt = 0,
        modifiedAt = modifiedAt,
        lastOpenedAt = 0,
        trashedAt = null,
    )

    @Test
    fun `the lock state is part of the preview key`() {
        val locked = note(locked = true)
        val unlocked = note(locked = false)

        assertThat(previewKey(locked, darkTheme = false))
            .isNotEqualTo(previewKey(unlocked, darkTheme = false))
    }

    @Test
    fun `removing a lock is reported exactly once`() {
        val tracker = PreviewInvalidation()
        tracker.observe(note(locked = true))
        assertThat(tracker.observe(note(locked = true))).isFalse()

        assertThat(tracker.observe(note(locked = false))).isTrue()
        // A later composition of the still-unlocked note is not a new unlock event.
        assertThat(tracker.observe(note(locked = false))).isFalse()
    }

    @Test
    fun `an unlock clears failures recorded before the lock`() {
        val tracker = PreviewInvalidation()
        val key = previewKey(note(locked = false), darkTheme = true)
        tracker.markFailed(key)
        assertThat(tracker.isFailed(key)).isTrue()

        tracker.observe(note(locked = true))
        tracker.observe(note(locked = false))

        assertThat(tracker.isFailed(key)).isFalse()
    }

    @Test
    fun `a note locked from the start is never a stale unlock event`() {
        val tracker = PreviewInvalidation()
        assertThat(tracker.observe(note(locked = true))).isFalse()
        assertThat(tracker.observe(note(locked = false))).isTrue()
        assertThat(tracker.observe(note(locked = false))).isFalse()
    }

    @Test
    fun `unchanged lock state does not invalidate`() {
        val tracker = PreviewInvalidation()
        tracker.observe(note(locked = false))
        val key = previewKey(note(locked = false), darkTheme = false)
        tracker.markFailed(key)

        assertThat(tracker.observe(note(locked = false))).isFalse()
        assertThat(tracker.isFailed(key)).isTrue()
    }

    @Test
    fun `forget drops failures and the remembered lock state`() {
        val tracker = PreviewInvalidation()
        val key = previewKey(note(locked = false), darkTheme = false)
        tracker.markFailed(key)
        tracker.observe(note(locked = true))

        tracker.forget(NoteId("n"))

        assertThat(tracker.isFailed(key)).isFalse()
        // The note is treated as newly seen, so its next unlocked observation is not an unlock event.
        assertThat(tracker.observe(note(locked = false))).isFalse()
    }

    @Test
    fun `different notes keep independent lock state`() {
        val tracker = PreviewInvalidation()
        tracker.observe(note(id = "a", locked = true))

        assertThat(tracker.observe(note(id = "b", locked = false))).isFalse()
        assertThat(tracker.observe(note(id = "a", locked = false))).isTrue()
    }
}

package org.samesame.notes.feature.notes

import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteId

/**
 * Tracks each note's lock state so that removing a lock invalidates its preview.
 *
 * A locked note never renders a preview, and its `modifiedAt` does not change when the lock is removed.
 * Without this tracker the card would keep the key it had while locked and never ask for a render again.
 * [PreviewKey] also carries the lock state, so a preview cached before locking is not reused for the
 * unlocked note, and a failed render from before the lock cannot block the request after it.
 */
internal class PreviewInvalidation {
    private val seenLocked = HashSet<NoteId>()
    private val failures = HashSet<PreviewKey>()

    /**
     * Records the note's current lock state and reports whether it just became unlocked. On that
     * transition every remembered failure for the note is dropped, so the next request is not skipped.
     */
    fun observe(note: Note): Boolean {
        if (note.isLocked) {
            seenLocked += note.id
            return false
        }
        val justUnlocked = seenLocked.remove(note.id)
        if (justUnlocked) failures.removeAll { it.id == note.id }
        return justUnlocked
    }

    fun isFailed(key: PreviewKey): Boolean = key in failures

    fun markFailed(key: PreviewKey) {
        failures += key
    }

    /** Drops everything remembered for a note that was removed or whose lock state was changed elsewhere. */
    fun forget(id: NoteId) {
        seenLocked -= id
        failures.removeAll { it.id == id }
    }
}

/** The cache identity of a note's first-page preview: every input that changes the rendered page is in the key. */
internal data class PreviewKey(
    val id: NoteId,
    val modifiedAt: Long,
    val darkTheme: Boolean,
    val locked: Boolean,
)

internal fun previewKey(note: Note, darkTheme: Boolean): PreviewKey =
    PreviewKey(note.id, note.modifiedAt, darkTheme, note.isLocked)

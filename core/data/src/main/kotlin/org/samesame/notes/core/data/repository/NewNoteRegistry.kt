package org.samesame.notes.core.data.repository

import java.util.Collections

/**
 * Notes created by the FAB in this process. The composer opens them in edit mode (keyboard up); every later open
 * of the same note (from the list, a link, a process restart) is an existing note and opens in reading mode.
 * Deliberately process-local: nothing is remembered between sessions.
 */
object NewNoteRegistry {
    private val ids: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun noteCreated(id: String) {
        ids.add(id)
    }

    /** True exactly once, for a note created by the FAB in this process. */
    fun take(id: String): Boolean = ids.remove(id)

    fun forget(id: String) {
        ids.remove(id)
    }

    internal fun clear() {
        ids.clear()
    }
}

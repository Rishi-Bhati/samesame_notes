package org.samesame.notes.core.model

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class FolderId(val value: String)

/** A user folder. Folders nest up to [Limits.MAX_FOLDER_DEPTH] levels. */
data class Folder(
    val id: FolderId,
    val parentId: FolderId?,
    val name: String,
    /** ARGB, or null for the default color. */
    val color: Long?,
    val sortOrder: Int,
    val depth: Int,
    val noteCount: Int,
    /** Epoch millis the folder was moved to Trash, or null when it isn't in Trash. */
    val trashedAt: Long? = null,
) {
    val isInTrash: Boolean get() = trashedAt != null

    /** Whole days left before Trash purges this folder, or null when it isn't in Trash. */
    fun daysLeftInTrash(now: Long): Int? {
        val trashedAt = trashedAt ?: return null
        val elapsedDays = ((now - trashedAt) / DAY_MILLIS).toInt()
        return (Limits.TRASH_RETENTION_DAYS - elapsedDays).coerceAtLeast(0)
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

/** What the notes list is showing: the drawer's predefined entries plus user folders. */
sealed interface NoteFilter {
    data object All : NoteFilter
    data object Uncategorized : NoteFilter
    data object Favorites : NoteFilter
    data object Locked : NoteFilter
    data object Trash : NoteFilter
    data class InFolder(val folderId: FolderId, val includeSubfolders: Boolean = true) : NoteFilter
    data class WithTag(val tag: String) : NoteFilter
}

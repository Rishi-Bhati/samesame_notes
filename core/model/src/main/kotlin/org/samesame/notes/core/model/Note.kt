package org.samesame.notes.core.model

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class NoteId(val value: String)

/**
 * Catalog view of a note: what the list, search and drawer need. Page and ink content live in the
 * note's document bundle, not here (see `:core:document`).
 */
data class Note(
    val id: NoteId,
    val title: String,
    val previewText: String,
    val folderId: FolderId?,
    val isFavorite: Boolean,
    val isLocked: Boolean,
    val pageMode: PageMode,
    val backgroundColor: Long,
    val createdAt: Long,
    val modifiedAt: Long,
    val lastOpenedAt: Long,
    /** Epoch millis the note was moved to Trash, or null when it isn't in Trash. */
    val trashedAt: Long?,
    val tags: List<String> = emptyList(),
) {
    val isInTrash: Boolean get() = trashedAt != null

    /** Whole days left before Trash purges this note, or null when it isn't in Trash. */
    fun daysLeftInTrash(now: Long): Int? {
        val trashedAt = trashedAt ?: return null
        val elapsedDays = ((now - trashedAt) / DAY_MILLIS).toInt()
        return (Limits.TRASH_RETENTION_DAYS - elapsedDays).coerceAtLeast(0)
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

/** Infinite scrolling page vs individual fixed-ratio pages. */
@Serializable
enum class PageMode { Infinite, Pages }

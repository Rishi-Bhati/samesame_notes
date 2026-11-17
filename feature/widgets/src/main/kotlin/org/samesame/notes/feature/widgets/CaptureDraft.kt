package org.samesame.notes.feature.widgets

import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText

/**
 * What the quick-capture sheet holds: an optional title and either free text or checklist items. Pure (JVM-tested): the
 * sheet only edits it, and saving turns it into a note body with [richText].
 */
data class CaptureDraft(
    val title: String = "",
    val body: String = "",
    val checklist: Boolean = false,
    val items: List<String> = listOf(""),
) {
    /** Nothing worth keeping: saving or dismissing creates no note. */
    val isEmpty: Boolean
        get() = title.isBlank() && if (checklist) items.all { it.isBlank() } else body.isBlank()

    /** Text mode -> checklist: each non-empty line becomes an item. */
    fun toChecklist(): CaptureDraft {
        if (checklist) return this
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return copy(checklist = true, items = lines.ifEmpty { listOf("") })
    }

    /** Checklist -> text mode: the items as lines. */
    fun toText(): CaptureDraft {
        if (!checklist) return this
        return copy(checklist = false, body = items.filter { it.isNotBlank() }.joinToString("\n"))
    }

    /** Adds [text] (dictated) at the end: a new line of text, or a new item (filling an empty last item). */
    fun append(text: String): CaptureDraft {
        val t = text.trim()
        if (t.isEmpty()) return this
        return if (checklist) {
            if (items.lastOrNull()?.isBlank() == true) copy(items = items.dropLast(1) + t) else copy(items = items + t)
        } else {
            copy(body = if (body.isBlank()) t else body.trimEnd() + (if (body.endsWith("\n")) "" else " ") + t)
        }
    }

    fun setItem(index: Int, text: String): CaptureDraft =
        if (index !in items.indices) this else copy(items = items.toMutableList().also { it[index] = text.replace("\n", " ") })

    /** A new empty item after [index] (Enter / Next on an item). */
    fun insertItemAfter(index: Int): CaptureDraft =
        copy(items = items.toMutableList().also { it.add((index + 1).coerceIn(0, it.size), "") })

    /** Removes the item at [index]; the list always keeps one (empty) item. */
    fun removeItem(index: Int): CaptureDraft {
        if (index !in items.indices) return this
        val left = items.toMutableList().also { it.removeAt(index) }
        return copy(items = left.ifEmpty { listOf("") })
    }

    /** The note body: plain paragraphs, or one unchecked checkbox paragraph per non-empty item. */
    fun richText(): RichText {
        if (!checklist) return RichText.of(body.trim('\n', ' '))
        val kept = items.map { it.trim() }.filter { it.isNotEmpty() }
        if (kept.isEmpty()) return RichText()
        val base = RichText.of(kept.joinToString("\n"))
        return base.copy(paragraphs = kept.map { ParagraphStyle(checkbox = true, checked = false) })
    }

    /** The note's title: the typed one (single line), else empty (the app then names it like any untitled note). */
    fun noteTitle(): String = title.replace('\n', ' ').trim()
}

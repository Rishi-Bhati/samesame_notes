package org.samesame.notes.feature.textdoc

import org.samesame.notes.core.model.Limits

/**
 * Undo and redo over whole text snapshots.
 *
 * A run of single-character insertions next to each other counts as one undo step, so typing a word is undone at once.
 * Every other change (paste, delete, replace) starts a new step. The stacks keep at most [limit] steps.
 */
internal class UndoRedoState(private val limit: Int = Limits.MAX_UNDO_STEPS) {
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private var current = ""

    /** Caret position after the previous single-character insertion, or -1 when the current step is closed. */
    private var typedEnd = -1

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val text: String get() = current

    /** Starts over from [text] with empty stacks (a freshly loaded document). */
    fun reset(text: String) {
        current = text
        undo.clear()
        redo.clear()
        typedEnd = -1
    }

    /** Records a change from the current text to [newText]. */
    fun edit(newText: String) {
        if (newText == current) return
        val insertion = singleCharInsertion(current, newText)
        val coalesce = insertion != null && insertion == typedEnd
        if (!coalesce) {
            push(undo, current)
            redo.clear()
        }
        current = newText
        typedEnd = insertion?.plus(1) ?: -1
    }

    /** The text before the last step, or null when there is nothing to undo. */
    fun undo(): String? {
        val previous = undo.removeLastOrNull() ?: return null
        push(redo, current)
        current = previous
        typedEnd = -1
        return current
    }

    /** The text after the last undone step, or null when there is nothing to redo. */
    fun redo(): String? {
        val next = redo.removeLastOrNull() ?: return null
        push(undo, current)
        current = next
        typedEnd = -1
        return current
    }

    private fun push(stack: ArrayDeque<String>, value: String) {
        stack.addLast(value)
        while (stack.size > limit) stack.removeFirst()
    }

    /** The index a single inserted character went to, or null when the change is anything else. */
    private fun singleCharInsertion(old: String, new: String): Int? {
        if (new.length != old.length + 1) return null
        var i = 0
        while (i < old.length && old[i] == new[i]) i++
        // Everything after the insertion must still line up.
        return if (old.regionMatches(i, new, i + 1, old.length - i)) i else null
    }
}

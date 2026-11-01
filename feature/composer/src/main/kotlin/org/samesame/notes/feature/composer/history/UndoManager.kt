package org.samesame.notes.feature.composer.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.samesame.notes.core.model.Limits

data class UndoState(val canUndo: Boolean = false, val canRedo: Boolean = false)

/**
 * One history per open note (doc 4.4). Main-thread only. Commands are recorded after the change was applied.
 *
 * Typing coalescing: consecutive [TextEdit]s merge into one entry (a "burst") until the burst breaks: a pause
 * over [pauseMs], whitespace after a word, a cursor jump (the edits are not contiguous), any other command
 * (style change, ink, ...), [breakBurst] (mode switch, selection jump, style toggle), or a paste-like
 * multi-character insert that is not an IME composition.
 */
class UndoManager(
    private val limit: Int = Limits.MAX_UNDO_STEPS,
    private val maxRetainedStrokes: Int = MAX_RETAINED_STROKES,
    private val pauseMs: Long = TYPING_PAUSE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val undoStack = ArrayList<EditCommand>()
    private val redoStack = ArrayList<EditCommand>()
    private var retained = 0

    private var burstOpen = false
    private var lastTextAt = 0L

    private var groupDepth = 0
    private var groupBuffer: ArrayList<EditCommand>? = null

    private val _state = MutableStateFlow(UndoState())
    val state: StateFlow<UndoState> = _state.asStateFlow()

    val undoSize: Int get() = undoStack.size
    val redoSize: Int get() = redoStack.size

    fun peekUndo(): EditCommand? = undoStack.lastOrNull()
    fun peekRedo(): EditCommand? = redoStack.lastOrNull()

    /** Ends the current typing burst (cursor jump, style toggle, mode switch). */
    fun breakBurst() {
        burstOpen = false
    }

    fun record(cmd: EditCommand) {
        val buffer = groupBuffer
        if (buffer != null) {
            buffer += cmd
            return
        }
        redoStack.clear()
        val now = clock()
        if (cmd is TextEdit) {
            val last = undoStack.lastOrNull()
            val standalone = !cmd.composed && cmd.inserted.length > 1
            if (burstOpen && !standalone && last is TextEdit && now - lastTextAt <= pauseMs) {
                val merged = last.mergeWith(cmd)
                if (merged != null) {
                    undoStack[undoStack.lastIndex] = merged
                    retained += merged.weight - last.weight
                    burstOpen = !closesBurst(merged as TextEdit)
                    lastTextAt = now
                    publish()
                    return
                }
            }
            push(cmd)
            burstOpen = !standalone && !closesBurst(cmd)
            lastTextAt = now
        } else {
            push(cmd)
            burstOpen = false
        }
        publish()
    }

    /** Everything recorded inside [block] becomes one undo step. */
    fun <T> group(block: () -> T): T {
        if (groupDepth++ == 0) groupBuffer = ArrayList()
        try {
            return block()
        } finally {
            if (--groupDepth == 0) {
                val cmds = groupBuffer.orEmpty()
                groupBuffer = null
                when (cmds.size) {
                    0 -> Unit
                    1 -> record(cmds[0])
                    else -> record(Group(cmds))
                }
            }
        }
    }

    fun undo(t: EditTarget): Boolean {
        val cmd = undoStack.removeLastOrNull() ?: return false
        retained -= cmd.weight
        burstOpen = false
        cmd.undo(t)
        redoStack += cmd
        publish()
        return true
    }

    fun redo(t: EditTarget): Boolean {
        val cmd = redoStack.removeLastOrNull() ?: return false
        burstOpen = false
        cmd.redo(t)
        undoStack += cmd
        retained += cmd.weight
        evict()
        publish()
        return true
    }

    /** Forgets the redo stack (a cancelled step that must not come back). */
    fun dropRedo() {
        redoStack.clear()
        publish()
    }

    fun clear() {
        undoStack.clear(); redoStack.clear(); retained = 0; burstOpen = false
        publish()
    }

    private fun push(cmd: EditCommand) {
        undoStack += cmd
        retained += cmd.weight
        evict()
    }

    private fun evict() {
        var drop = 0
        while (undoStack.size - drop > limit || (retained > maxRetainedStrokes && undoStack.size - drop > 1)) {
            retained -= undoStack[drop].weight
            drop++
        }
        if (drop > 0) undoStack.subList(0, drop).clear()
    }

    private fun publish() {
        val s = UndoState(undoStack.isNotEmpty(), redoStack.isNotEmpty())
        if (_state.value != s) _state.value = s
    }

    private fun closesBurst(e: TextEdit): Boolean {
        val text = e.inserted.text
        if (text.isEmpty()) return false
        val last = text.last()
        if (!last.isWhitespace()) return false
        // Whitespace after a word ends the burst; further whitespace keeps going in the next one.
        return text.length < 2 || !text[text.length - 2].isWhitespace()
    }

    companion object {
        const val TYPING_PAUSE_MS = 1000L
        const val MAX_RETAINED_STROKES = 50_000
    }
}

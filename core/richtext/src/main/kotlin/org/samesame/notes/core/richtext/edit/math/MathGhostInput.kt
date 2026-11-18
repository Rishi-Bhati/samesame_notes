package org.samesame.notes.core.richtext.edit.math

/**
 * What the keyboard is told about the transient math ghost, and which keyboard events accept it. Pure so it is JVM-testable.
 *
 * The keyboard's spacebar glide is clamped to the text it believes follows the caret, so the ghost has to be part of that
 * text in every query ([withGhost]) and the keyboard has to be re-synced whenever the ghost appears, changes or goes
 * ([ImeGhostSync]).
 */
object MathGhostInput {
    /** Ordinary `=` expressions keep Enter as a newline; total keywords accept their result. */
    fun enterAccepts(ghost: MathSuggestion?): Boolean = ghost?.keywordLine == true

    /** [text] as the keyboard must see it: the ghost inserted at [anchor] (the caret stays in front of it). */
    fun withGhost(text: CharSequence, anchor: Int, ghost: String): String {
        val a = anchor.coerceIn(0, text.length)
        return StringBuilder(text.length + ghost.length).append(text, 0, a).append(ghost).append(text, a, text.length).toString()
    }

    /** Text after the caret as the keyboard sees it: ghost first, then the real text after it. */
    fun afterCursor(text: CharSequence, caret: Int, ghost: String, n: Int): String {
        val c = caret.coerceIn(0, text.length)
        return (ghost + text.subSequence(c, text.length)).take(n)
    }

    /** True when a collapsed `setSelection(start, end)` request moves the caret to the right of [caret] (a glide or arrow right). */
    fun movesRight(caret: Int, start: Int, end: Int): Boolean = start == end && start > caret
}

/** What to do with an IME `setSelection` while a ghost is showing. */
enum class SelectionVerdict { Pass, Accept }

/**
 * Judges setSelection requests while a ghost is showing. A request to the right accepts. A glide that the keyboard
 * clamped (it thought no text followed the caret) arrives as `setSelection(caret, caret)` several times in a few
 * milliseconds; [STALL_COUNT] of those within [STALL_WINDOW_MS] also accept (safety net for a stale keyboard model).
 */
class GhostSelectionJudge {
    private var stallCount = 0
    private var stallStart = 0L

    fun onSelection(caret: Int, start: Int, end: Int, nowMs: Long): SelectionVerdict {
        if (MathGhostInput.movesRight(caret, start, end)) {
            reset()
            return SelectionVerdict.Accept
        }
        if (start == end && start == caret) {
            if (stallCount == 0 || nowMs - stallStart > STALL_WINDOW_MS) {
                stallCount = 1
                stallStart = nowMs
            } else {
                stallCount++
            }
            if (stallCount >= STALL_COUNT) {
                reset()
                return SelectionVerdict.Accept
            }
            return SelectionVerdict.Pass
        }
        reset()
        return SelectionVerdict.Pass
    }

    fun reset() {
        stallCount = 0
        stallStart = 0L
    }

    companion object {
        const val STALL_COUNT = 3
        const val STALL_WINDOW_MS = 250L
    }
}

/**
 * Double space accepts: a space committed right after `=` keeps the ghost, a second space within [WINDOW_MS] accepts
 * and is swallowed. Gboard's "double space makes a period" arrives as a delete of one char followed by a commit of
 * ". " (or "."): with a ghost showing and a space just typed that is the same gesture and is swallowed too.
 */
class GhostDoubleSpace(private val windowMs: Long = WINDOW_MS) {
    private var lastSpaceMs = NONE
    private var lastDeleteMs = NONE

    /** Call for every committed text. [ghostShowing] = a ghost sits at the caret before this commit. True = accept the ghost and swallow [text]. */
    fun onCommit(text: String, nowMs: Long, ghostShowing: Boolean, charBeforeCaretIsSpace: Boolean): Boolean {
        val spaceRecently = lastSpaceMs != NONE && nowMs - lastSpaceMs <= windowMs
        val deletedRecently = lastDeleteMs != NONE && nowMs - lastDeleteMs <= DELETE_WINDOW_MS
        if (ghostShowing && spaceRecently) {
            if (text == " " && charBeforeCaretIsSpace) {
                reset()
                return true
            }
            if ((text == ". " || text == ".") && deletedRecently) {
                reset()
                return true
            }
        }
        if (text == " " && ghostShowing) lastSpaceMs = nowMs else if (!(deletedRecently && text.startsWith("."))) lastSpaceMs = NONE
        return false
    }

    /** Call when the keyboard deletes text backwards (deleteSurroundingText or DEL). */
    fun onDelete(nowMs: Long) {
        lastDeleteMs = nowMs
    }

    fun reset() {
        lastSpaceMs = NONE
        lastDeleteMs = NONE
    }

    companion object {
        const val WINDOW_MS = 400L
        const val DELETE_WINDOW_MS = 150L
        private const val NONE = Long.MIN_VALUE
    }
}

/**
 * Decides when the keyboard must be re-synced (restartInput) because the ghost it can see changed. Only when nothing
 * is being composed, so a word the user is typing is never cut.
 */
class ImeGhostSync {
    private var lastSyncedKey: String? = null

    /** [ghost] now (anchor + text, or null). Returns true when a re-sync is due. */
    fun onGhost(anchor: Int, text: String?, composing: Boolean): Boolean {
        if (composing) {
            // Cannot restart mid-composition; remember nothing so the next quiet change still syncs.
            return false
        }
        val key = text?.let { "$anchor:$it" }
        if (key == lastSyncedKey) return false
        lastSyncedKey = key
        return true
    }
}

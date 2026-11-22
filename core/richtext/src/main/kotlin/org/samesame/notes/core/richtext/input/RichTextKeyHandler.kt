package org.samesame.notes.core.richtext.input

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import org.samesame.notes.core.richtext.BodyText
import org.samesame.notes.core.richtext.edit.math.MathGhostInput

/** Character style toggled by a keyboard shortcut (Ctrl+B / Ctrl+I / Ctrl+U). */
enum class KeyStyle { Bold, Italic, Underline }

/**
 * Key events for the body, shared by the IME ([RichTextInputConnection.sendKeyEvent]) and hardware keyboards
 * ([TextInputNode] key input: physical, Bluetooth and cover keyboards, Chromebooks, DeX, `adb shell input`).
 *
 * The two paths never see the same event: an IME key arrives through `sendKeyEvent` (which does not forward it to the
 * view), a hardware key the IME did not consume arrives at the focused view. So each event is applied exactly once.
 *
 * Held by the editor state (not the connection, which is recreated on every restartInput) so the dead-key accent and
 * the vertical-move goal column survive.
 */
class RichTextKeyHandler(private val body: BodyText, private val host: InputHost) {

    /** A dead key (an accent) waiting for the next character, from [KeyCharacterMap.COMBINING_ACCENT]. */
    private var pendingAccent = 0

    /** Goal x for Up/Down runs, valid while the caret focus is still at [goalOffset]. */
    private var goalX = 0f
    private var goalOffset = -1

    /**
     * Handles [event]; true when consumed. [fromIme]: it came from the IME's `sendKeyEvent`, which already wraps it in a
     * body batch. Key ups are not handled here (the caller decides; see [TextInputNode]).
     */
    fun onKey(event: KeyEvent, fromIme: Boolean): Boolean {
        if (event.action == KeyEvent.ACTION_MULTIPLE && event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            val chars = event.characters ?: return false
            return typeText(chars, fromIme)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return if (fromIme) keyDown(event, true) else body.batch { keyDown(event, false) }
    }

    private fun keyDown(event: KeyEvent, fromIme: Boolean): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) goalOffset = -1
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        val shift = event.isShiftPressed
        val alt = event.isAltPressed
        if (ctrl) return shortcut(code, shift)
        val sel = body.selection
        when (code) {
            KeyEvent.KEYCODE_DEL -> {
                val caretBefore = sel.max
                val pageBefore = host.currentCaretPage()
                host.ghostDoubleSpace.onDelete(SystemClock.uptimeMillis())
                body.deleteBackward()
                val caretAfter = body.selection.start
                if (caretAfter < caretBefore) host.onUserBackspace(pageBefore, caretBefore, caretAfter)
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> body.deleteForward()
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (alt) return false
                enter()
            }
            KeyEvent.KEYCODE_TAB -> {
                if (alt) return false
                val ghost = host.mathGhostAtCaret()
                if (ghost != null && !shift) {
                    ImeTrace.logMath("key TAB anchor=${ghost.anchor} caret=${sel.start},${sel.end}")
                    host.acceptMathGhostFromIme("tab")
                } else {
                    if (shift) body.outdent() else body.indent()
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> moveHorizontal(-1, shift, byWord = false)
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val ghost = host.mathGhostAtCaret()
                if (ghost != null && !shift) {
                    ImeTrace.logMath("key DPAD_RIGHT anchor=${ghost.anchor} caret=${sel.start},${sel.end}")
                    host.acceptMathGhostFromIme("dpad_right")
                } else {
                    moveHorizontal(1, shift, byWord = false)
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> return moveVertical(-1, shift)
            KeyEvent.KEYCODE_DPAD_DOWN -> return moveVertical(1, shift)
            KeyEvent.KEYCODE_MOVE_HOME -> moveTo(lineEdge(sel.end, end = false), shift)
            KeyEvent.KEYCODE_MOVE_END -> moveTo(lineEdge(sel.end, end = true), shift)
            else -> return typeKey(event, fromIme)
        }
        return true
    }

    /** Ctrl (or Meta, the Chromebook / Mac command key) shortcuts. */
    private fun shortcut(code: Int, shift: Boolean): Boolean {
        val sel = body.selection
        when (code) {
            KeyEvent.KEYCODE_Z -> return if (shift) host.redo() else host.undo()
            KeyEvent.KEYCODE_Y -> return host.redo()
            KeyEvent.KEYCODE_A -> if (!host.selectAll()) body.selectAll()
            KeyEvent.KEYCODE_C -> host.copy()
            KeyEvent.KEYCODE_X -> host.cut()
            KeyEvent.KEYCODE_V -> host.paste()
            KeyEvent.KEYCODE_B -> return host.toggleStyle(KeyStyle.Bold)
            KeyEvent.KEYCODE_I -> return host.toggleStyle(KeyStyle.Italic)
            KeyEvent.KEYCODE_U -> return host.toggleStyle(KeyStyle.Underline)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveHorizontal(-1, shift, byWord = true)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveHorizontal(1, shift, byWord = true)
            KeyEvent.KEYCODE_MOVE_HOME -> moveTo(0, shift)
            KeyEvent.KEYCODE_MOVE_END -> moveTo(body.length, shift)
            KeyEvent.KEYCODE_DEL -> {
                if (!sel.collapsed) return false
                val to = wordBoundary(sel.min, -1)
                if (to == sel.min) return true
                body.setSelection(to, sel.min)
                body.deleteBackward()
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (!sel.collapsed) return false
                val to = wordBoundary(sel.max, 1)
                if (to == sel.max) return true
                body.setSelection(sel.max, to)
                body.deleteForward()
            }
            else -> return false
        }
        return true
    }

    private fun enter() {
        if (host.slashEnterFromIme()) return
        if (body.composition == null && MathGhostInput.enterAccepts(host.mathGhostAtCaret()) &&
            host.acceptMathGhostFromIme("enter")
        ) return
        body.insertNewline()
    }

    /** A printable key: its character for the current modifiers (Shift, AltGr), with dead keys combined. */
    private fun typeKey(event: KeyEvent, fromIme: Boolean): Boolean {
        val raw = event.getUnicodeChar(event.metaState)
        if (raw == 0) return false
        if (raw and KeyCharacterMap.COMBINING_ACCENT != 0) {
            pendingAccent = raw and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }
        if (Character.isISOControl(raw)) return false
        val accent = pendingAccent
        pendingAccent = 0
        val text = if (accent != 0) {
            val combined = KeyEvent.getDeadChar(accent, raw)
            if (combined != 0) String(Character.toChars(combined))
            else String(Character.toChars(accent)) + String(Character.toChars(raw))
        } else {
            String(Character.toChars(raw))
        }
        return typeText(text, fromIme)
    }

    /**
     * Types [text] at the selection like an IME commit: the math ghost's double space, the typing shortcuts. A hardware
     * key that arrives while an IME composition is open first ends that composition (the IME gets the new selection); a
     * key the IME itself sends replaces the selection and leaves the composition to the IME, as before.
     */
    private fun typeText(text: String, fromIme: Boolean): Boolean {
        if (!fromIme && (body.composition != null || body.imeComposing)) {
            BaseInputConnection.removeComposingSpans(body.editable)
            body.imeComposing = false
        }
        val sel = body.selection
        val spaceBefore = sel.collapsed && sel.min > 0 && body.editable[sel.min - 1] == ' '
        val ghost = host.mathGhostAtCaret()
        if (host.ghostDoubleSpace.onCommit(text, SystemClock.uptimeMillis(), ghost != null && body.composition == null, spaceBefore) &&
            host.acceptMathGhostFromIme("doubleSpace")
        ) return true
        if (text == "\n") enter() else body.commitText(text)
        return true
    }

    // ---------------------------------------------------------------- caret movement

    private fun moveTo(offset: Int, extend: Boolean) {
        val sel = body.selection
        val o = offset.coerceIn(0, body.length)
        if (extend) body.setSelection(sel.start, o) else body.setSelection(o)
    }

    private fun moveHorizontal(dir: Int, extend: Boolean, byWord: Boolean) {
        val sel = body.selection
        if (!extend && !sel.collapsed && !byWord) {
            body.setSelection(if (dir < 0) sel.min else sel.max)
            return
        }
        val from = if (extend) sel.end else if (dir < 0) sel.min else sel.max
        val to = if (byWord) wordBoundary(from, dir) else stepChar(from, dir)
        moveTo(to, extend)
    }

    /** One character left/right, not splitting a surrogate pair. */
    private fun stepChar(from: Int, dir: Int): Int {
        val text = body.editable
        return if (dir < 0) {
            if (from <= 0) 0
            else if (from >= 2 && Character.isLowSurrogate(text[from - 1]) && Character.isHighSurrogate(text[from - 2])) from - 2
            else from - 1
        } else {
            if (from >= body.length) body.length
            else if (from + 1 < body.length && Character.isHighSurrogate(text[from]) && Character.isLowSurrogate(text[from + 1])) from + 2
            else from + 1
        }
    }

    /** Ctrl+Left / Ctrl+Right: skips separators, then a run of letters/digits (the start / end of a word). */
    private fun wordBoundary(from: Int, dir: Int): Int {
        val text = body.editable
        var i = from
        if (dir < 0) {
            while (i > 0 && !isWordChar(text[i - 1])) i--
            while (i > 0 && isWordChar(text[i - 1])) i--
        } else {
            while (i < body.length && !isWordChar(text[i])) i++
            while (i < body.length && isWordChar(text[i])) i++
        }
        return i
    }

    private fun isWordChar(c: Char) = Character.isLetterOrDigit(c) || c == '_' || Character.isSurrogate(c)

    private fun moveVertical(dir: Int, extend: Boolean): Boolean {
        val sel = body.selection
        val from = if (extend) sel.end else if (dir < 0) sel.min else sel.max
        if (!extend && !sel.collapsed) {
            moveTo(from, extend = false)
            return true
        }
        val goal = if (goalOffset == from) goalX else null
        val (to, x) = host.lineMove(from, dir, goal) ?: return false
        moveTo(to, extend)
        goalX = x
        goalOffset = body.selection.end
        return true
    }

    private fun lineEdge(offset: Int, end: Boolean): Int = host.lineEdge(offset, end) ?: if (end) body.length else 0
}

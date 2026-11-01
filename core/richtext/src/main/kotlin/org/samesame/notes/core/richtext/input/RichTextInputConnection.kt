package org.samesame.notes.core.richtext.input

import android.os.Build
import android.text.Editable
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.richtext.BodyText

/** What the IME connection needs from the editor besides the [BodyText]. */
interface InputHost {
    fun cut()
    fun copy()
    fun paste()

    /** Called after every IME call that may have changed text, selection or composition. */
    fun onImeChanged()
}

/**
 * IME bridge over [BodyText.editable]. `BaseInputConnection` provides composing regions (Gboard,
 * Hangul syllable composition) on the Editable; every call is wrapped in a body batch so a whole IME
 * batch or composition becomes one edit event, and Enter/Backspace go through the body's list rules.
 */
class RichTextInputConnection(
    view: View,
    private val body: BodyText,
    private val host: InputHost,
) : BaseInputConnection(view, true) {

    override fun getEditable(): Editable = body.editable

    private inline fun <T> edit(block: () -> T): T {
        body.beginBatch()
        try {
            return block()
        } finally {
            body.endBatch()
            host.onImeChanged()
        }
    }

    override fun beginBatchEdit(): Boolean {
        body.beginBatch()
        return true
    }

    override fun endBatchEdit(): Boolean {
        body.endBatch()
        host.onImeChanged()
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = edit {
        val t = text?.toString().orEmpty().replace(OBJECT_CHAR.toString(), "")
        if (t == "\n" && body.composition == null) {
            body.insertNewline()
            true
        } else {
            // Plain string: IME styling must not leak into the body.
            val ok = super.commitText(t, newCursorPosition)
            if (ok) body.applyImeTypingShortcuts(t)
            ok
        }
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = edit {
        val t = text ?: ""
        body.imeComposing = true
        // Keep the IME's spans (composing underline); they are removed when composition finishes.
        super.setComposingText(if (t.contains(OBJECT_CHAR)) t.toString().replace(OBJECT_CHAR.toString(), "") else t, newCursorPosition)
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean = edit {
        body.imeComposing = true
        super.setComposingRegion(start, end)
    }

    override fun finishComposingText(): Boolean = edit { super.finishComposingText() }

    override fun setSelection(start: Int, end: Int): Boolean = edit { super.setSelection(start, end) }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = edit {
        val sel = body.selection
        if (beforeLength == 1 && afterLength == 0 && sel.collapsed && body.composition == null) {
            body.deleteBackward()
            true
        } else {
            super.deleteSurroundingText(beforeLength, afterLength)
        }
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        edit { super.deleteSurroundingTextInCodePoints(beforeLength, afterLength) }

    override fun sendKeyEvent(event: KeyEvent): Boolean = edit {
        if (event.action != KeyEvent.ACTION_DOWN) return@edit true
        val sel = body.selection
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> body.deleteBackward()
            KeyEvent.KEYCODE_FORWARD_DEL -> body.deleteForward()
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> body.insertNewline()
            KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) body.outdent() else body.indent()
            KeyEvent.KEYCODE_DPAD_LEFT -> body.setSelection(if (sel.collapsed) (sel.min - 1).coerceAtLeast(0) else sel.min)
            KeyEvent.KEYCODE_DPAD_RIGHT -> body.setSelection(if (sel.collapsed) (sel.max + 1).coerceAtMost(body.length) else sel.max)
            KeyEvent.KEYCODE_MOVE_HOME -> body.setSelection(0)
            KeyEvent.KEYCODE_MOVE_END -> body.setSelection(body.length)
            else -> {
                val c = event.getUnicodeChar(event.metaState)
                if (c != 0 && !Character.isISOControl(c)) body.commitText(String(Character.toChars(c)))
                else return@edit false
            }
        }
        true
    }

    override fun performContextMenuAction(id: Int): Boolean = edit {
        when (id) {
            android.R.id.selectAll -> body.selectAll()
            android.R.id.cut -> host.cut()
            android.R.id.copy -> host.copy()
            android.R.id.paste, android.R.id.pasteAsPlainText -> host.paste()
            else -> return@edit false
        }
        true
    }

    override fun performEditorAction(actionCode: Int): Boolean = edit {
        body.insertNewline()
        true
    }

    override fun closeConnection() {
        edit { super.closeConnection() }
    }

    companion object {
        /** Fills [outAttrs] for a multi-line rich text body. */
        fun configure(outAttrs: EditorInfo, body: BodyText) {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_NONE
            val sel = body.selection
            outAttrs.initialSelStart = sel.start
            outAttrs.initialSelEnd = sel.end
            outAttrs.initialCapsMode = getCursorCapsMode(body, outAttrs.inputType)
            if (Build.VERSION.SDK_INT >= 30) outAttrs.setInitialSurroundingText(body.editable)
        }

        private fun getCursorCapsMode(body: BodyText, inputType: Int): Int =
            android.text.TextUtils.getCapsMode(body.editable, body.selection.min, inputType)
    }
}

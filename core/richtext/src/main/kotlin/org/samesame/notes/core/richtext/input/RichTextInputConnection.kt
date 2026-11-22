package org.samesame.notes.core.richtext.input

import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.richtext.BodyText
import org.samesame.notes.core.richtext.edit.math.MathGhostInput
import org.samesame.notes.core.richtext.edit.math.SelectionVerdict

/** What the IME connection needs from the editor besides the [BodyText]. */
interface InputHost {
    fun cut()
    fun copy()
    fun paste()

    /**
     * A whole text the keyboard committed in one go (a clipboard chip): true when the host took it as a paste (library
     * file links, see `RichTextEditorState.onPasteFileLinks`), false to insert it as typed text.
     */
    fun pasteText(text: String): Boolean = false

    /** Called after every IME call that may have changed text, selection or composition. */
    fun onImeChanged()

    /** The inline math suggestion shown at the collapsed caret, if any. */
    fun mathGhostAtCaret(): org.samesame.notes.core.richtext.edit.math.MathSuggestion? = null

    /** Inserts the math suggestion at the caret as real text. Returns true when accepted. */
    fun acceptMathGhostFromIme(reason: String): Boolean = false

    /** Enter while the `///` file picker is open: true when it inserted a row (no newline). */
    fun slashEnterFromIme(): Boolean = false

    /** Keyboard-event state for the ghost; held by the editor because the connection is recreated on every restartInput. */
    val ghostJudge: org.samesame.notes.core.richtext.edit.math.GhostSelectionJudge
        get() = org.samesame.notes.core.richtext.edit.math.GhostSelectionJudge()
    val ghostDoubleSpace: org.samesame.notes.core.richtext.edit.math.GhostDoubleSpace
        get() = org.samesame.notes.core.richtext.edit.math.GhostDoubleSpace()

    /** Current caret page index (0-based) in document space. */
    fun currentCaretPage(): Int = 0

    /** Called when the user deletes backwards with Backspace / IME delete. */
    fun onUserBackspace(pageBefore: Int, caretBefore: Int, caretAfter: Int) = Unit

    /** Ctrl+Z. True when the host has an undo history and took the key. */
    fun undo(): Boolean = false

    /** Ctrl+Y / Ctrl+Shift+Z. */
    fun redo(): Boolean = false

    /** Ctrl+A: true when the host selected (with its handles); false selects the body plainly. */
    fun selectAll(): Boolean = false

    /** Ctrl+B / Ctrl+I / Ctrl+U. True when applied. */
    fun toggleStyle(style: KeyStyle): Boolean = false

    /**
     * Up/Down: the offset [lines] visual lines from [offset] and the x it keeps as the goal column ([goalX] null = the
     * caret's own x). Null when the layout is not known (the key is then not handled).
     */
    fun lineMove(offset: Int, lines: Int, goalX: Float?): Pair<Int, Float>? = null

    /** Home/End: the start / end of the visual line holding [offset]; null = the start / end of the body. */
    fun lineEdge(offset: Int, end: Boolean): Int? = null
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
    /** Shared with the hardware keyboard path ([TextInputNode]); the editor state owns it. */
    private val keys: RichTextKeyHandler = RichTextKeyHandler(body, host),
) : BaseInputConnection(view, true) {

    init { ImeTrace.init(view.context) }

    private fun st(): String = "caret=${body.selection.start},${body.selection.end} comp=${body.composition} len=${body.length}"

    private fun q(t: CharSequence?): String = "\"" + t.toString().replace("\n", "\\n") + "\""

    override fun getEditable(): Editable = body.editable

    private inline fun <T> edit(name: String = "", args: String = "", block: () -> T): T {
        val before = if (ImeTrace.enabled && name.isNotEmpty()) st() else ""
        var result: T? = null
        try {
            body.beginBatch()
            result = block()
            return result
        } finally {
            body.endBatch()
            host.onImeChanged()
            if (ImeTrace.enabled && name.isNotEmpty()) ImeTrace.log("$name($args) -> $result | before: $before | after: ${st()}")
        }
    }

    override fun beginBatchEdit(): Boolean {
        ImeTrace.log("beginBatchEdit() ${st()}")
        body.beginBatch()
        return true
    }

    override fun endBatchEdit(): Boolean {
        body.endBatch()
        host.onImeChanged()
        ImeTrace.log("endBatchEdit() ${st()}")
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = edit("commitText", "${q(text)},$newCursorPosition") {
        val t = text?.toString().orEmpty().replace(OBJECT_CHAR.toString(), "")
        val ghostNow = host.mathGhostAtCaret()
        val caretNow = body.selection
        val spaceBefore = caretNow.collapsed && caretNow.min > 0 && body.editable[caretNow.min - 1] == ' '
        if (host.ghostDoubleSpace.onCommit(t, SystemClock.uptimeMillis(), ghostNow != null && body.composition == null, spaceBefore) &&
            host.acceptMathGhostFromIme("doubleSpace")
        ) {
            true
        } else if (t == "\n" && body.composition == null) {
            if (!host.slashEnterFromIme()) enter()
            true
        } else if (body.composition == null && t.length > MIN_LINK_LENGTH && host.pasteText(t)) {
            true
        } else {
            // Plain string: IME styling must not leak into the body.
            val ok = super.commitText(t, newCursorPosition)
            if (ok) body.applyImeTypingShortcuts(t)
            ok
        }
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = edit("setComposingText", "${q(text)},$newCursorPosition") {
        val t = text ?: ""
        body.imeComposing = true
        // Keep the IME's spans (composing underline); they are removed when composition finishes.
        super.setComposingText(if (t.contains(OBJECT_CHAR)) t.toString().replace(OBJECT_CHAR.toString(), "") else t, newCursorPosition)
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean = edit("setComposingRegion", "$start,$end") {
        body.imeComposing = true
        super.setComposingRegion(start, end)
    }

    override fun finishComposingText(): Boolean = edit("finishComposingText") { super.finishComposingText() }

    override fun setSelection(start: Int, end: Int): Boolean = edit("setSelection", "$start,$end") {
        val ghost = host.mathGhostAtCaret()
        if (ghost != null) {
            val caret = body.selection
            ImeTrace.logMath("ime setSelection req=$start,$end caret=${caret.start},${caret.end} anchor=${ghost.anchor} len=${body.length}")
            if (caret.collapsed &&
                host.ghostJudge.onSelection(caret.end, start, end, SystemClock.uptimeMillis()) == SelectionVerdict.Accept
            ) {
                if (host.acceptMathGhostFromIme("setSelection")) return@edit true
            }
        }
        super.setSelection(start, end)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = edit("deleteSurroundingText", "$beforeLength,$afterLength") {
        val sel = body.selection
        val caretBefore = sel.max
        val pageBefore = host.currentCaretPage()
        host.ghostDoubleSpace.onDelete(SystemClock.uptimeMillis())
        val ok = if (beforeLength == 1 && afterLength == 0 && sel.collapsed && body.composition == null) {
            body.deleteBackward()
            true
        } else {
            super.deleteSurroundingText(beforeLength, afterLength)
        }
        val caretAfter = body.selection.start
        if (beforeLength > 0 && caretAfter < caretBefore) host.onUserBackspace(pageBefore, caretBefore, caretAfter)
        ok
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        edit("deleteSurroundingTextInCodePoints", "$beforeLength,$afterLength") {
            val sel = body.selection
            val caretBefore = sel.max
            val pageBefore = host.currentCaretPage()
            host.ghostDoubleSpace.onDelete(SystemClock.uptimeMillis())
            val ok = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            val caretAfter = body.selection.start
            if (beforeLength > 0 && caretAfter < caretBefore) host.onUserBackspace(pageBefore, caretBefore, caretAfter)
            ok
        }

    override fun sendKeyEvent(event: KeyEvent): Boolean = edit("sendKeyEvent", "action=${event.action} key=${KeyEvent.keyCodeToString(event.keyCode)} meta=${event.metaState}") {
        // Not forwarded to the view (no super call): the hardware path in TextInputNode never sees IME keys.
        if (event.action == KeyEvent.ACTION_UP) return@edit true
        keys.onKey(event, fromIme = true)
    }

    override fun performContextMenuAction(id: Int): Boolean = edit("performContextMenuAction", "$id") {
        when (id) {
            android.R.id.selectAll -> body.selectAll()
            android.R.id.cut -> host.cut()
            android.R.id.copy -> host.copy()
            android.R.id.paste, android.R.id.pasteAsPlainText -> host.paste()
            else -> return@edit false
        }
        true
    }

    override fun performEditorAction(actionCode: Int): Boolean = edit("performEditorAction", "$actionCode") {
        if (!host.slashEnterFromIme()) enter()
        true
    }

    private fun enter() {
        if (body.composition == null && MathGhostInput.enterAccepts(host.mathGhostAtCaret()) &&
            host.acceptMathGhostFromIme("enter")
        ) return
        body.insertNewline()
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        if (n <= 0) return ""
        return ImeTrace.call("getTextBeforeCursor", "$n,$flags") { super.getTextBeforeCursor(n, flags) }
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        if (n <= 0) return ""
        val ghost = host.mathGhostAtCaret()
        if (ghost != null) {
            ImeTrace.logMath("ime getTextAfterCursor n=$n caret=${body.selection.start},${body.selection.end}")
            return ImeTrace.call("getTextAfterCursor[ghost]", "$n,$flags ${st()}") {
                MathGhostInput.afterCursor(body.editable, body.selection.max, ghost.text, n)
            }
        }
        return ImeTrace.call("getTextAfterCursor", "$n,$flags ${st()}") { super.getTextAfterCursor(n, flags) }
    }

    override fun getSelectedText(flags: Int): CharSequence? =
        ImeTrace.call("getSelectedText", "$flags") { super.getSelectedText(flags) }

    @androidx.annotation.RequiresApi(31)
    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): android.view.inputmethod.SurroundingText? =
        ImeTrace.call("getSurroundingText", "$beforeLength,$afterLength,$flags ${st()}") {
            val ghost = host.mathGhostAtCaret()
            if (ghost == null) {
                super.getSurroundingText(beforeLength, afterLength, flags)
            } else {
                val sel = body.selection
                val full = MathGhostInput.withGhost(body.editable, ghost.anchor, ghost.text)
                val start = (sel.min - beforeLength.coerceAtLeast(0)).coerceAtLeast(0)
                val end = (sel.max + afterLength.coerceAtLeast(0) + ghost.text.length).coerceAtMost(full.length)
                android.view.inputmethod.SurroundingText(full.substring(start, end), sel.min - start, sel.max - start, start)
            }
        }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean =
        ImeTrace.call("requestCursorUpdates", "$cursorUpdateMode") { super.requestCursorUpdates(cursorUpdateMode) }

    @androidx.annotation.RequiresApi(34)
    override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean =
        ImeTrace.call("requestCursorUpdates", "$cursorUpdateMode,$cursorUpdateFilter") { super.requestCursorUpdates(cursorUpdateMode, cursorUpdateFilter) }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
        ImeTrace.call("getExtractedText", "req=${request?.let { "token=${it.token} flags=${it.flags} hint=${it.hintMaxChars}x${it.hintMaxLines}" }} flags=$flags ${st()}") {
            getExtractedTextImpl(request, flags)
        }

    private fun getExtractedTextImpl(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        val base = super.getExtractedText(request, flags)
        val ghost = host.mathGhostAtCaret() ?: return base
        val et = base ?: ExtractedText().also {
            it.selectionStart = body.selection.start
            it.selectionEnd = body.selection.end
        }
        val whole = MathGhostInput.withGhost(body.editable, ghost.anchor, ghost.text)
        et.text = whole
        et.startOffset = 0
        et.partialStartOffset = -1
        et.partialEndOffset = -1
        return et
    }

    override fun closeConnection() {
        edit { super.closeConnection() }
    }

    companion object {
        /** Shorter commits can't be a file link (`samesame://file/` + a 36-character id), so they skip the paste check. */
        private const val MIN_LINK_LENGTH = 50

        /** Fills [outAttrs] for a multi-line rich text body. */
        fun configure(outAttrs: EditorInfo, body: BodyText, ghost: org.samesame.notes.core.richtext.edit.math.MathSuggestion? = null) {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_NONE
            val sel = body.selection
            outAttrs.initialSelStart = sel.start
            outAttrs.initialSelEnd = sel.end
            outAttrs.initialCapsMode = getCursorCapsMode(body, outAttrs.inputType)
            if (Build.VERSION.SDK_INT >= 30) {
                outAttrs.setInitialSurroundingText(
                    if (ghost != null && sel.collapsed && sel.min == ghost.anchor) MathGhostInput.withGhost(body.editable, ghost.anchor, ghost.text)
                    else body.editable,
                )
            }
        }

        private fun getCursorCapsMode(body: BodyText, inputType: Int): Int =
            android.text.TextUtils.getCapsMode(body.editable, body.selection.min, inputType)
    }
}

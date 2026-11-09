package org.samesame.notes.core.richtext

import android.text.Editable
import android.text.Selection
import android.text.SpanWatcher
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.view.inputmethod.BaseInputConnection
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.RichTextOps
import org.samesame.notes.core.model.richtext.TextAlign
import org.samesame.notes.core.model.richtext.TextRun
import org.samesame.notes.core.model.richtext.listMarkerPrefix
import org.samesame.notes.core.richtext.edit.ClipboardText
import org.samesame.notes.core.richtext.edit.EditAccumulator
import org.samesame.notes.core.richtext.edit.ParagraphRules
import org.samesame.notes.core.richtext.edit.TextEditEvent
import org.samesame.notes.core.richtext.edit.TextSelection
import org.samesame.notes.core.richtext.edit.InlineMarker
import org.samesame.notes.core.richtext.edit.TypingShortcuts
import org.samesame.notes.core.richtext.spans.CharStyleSpan
import org.samesame.notes.core.richtext.spans.InlineObjectSpan
import org.samesame.notes.core.richtext.spans.LinePitchSpan
import org.samesame.notes.core.richtext.spans.ParagraphSpan
import org.samesame.notes.core.richtext.spans.TextRenderEnv

/**
 * The note body while it is being edited (doc §5: owned by the session, survives rotation).
 *
 * Holds the [editable] (character styles and objects as spans, see [RichTextBridge]), the
 * authoritative paragraph-style list, the selection and the typing style, and reports every user
 * edit as a [TextEditEvent] to [addEditListener]. Every mutation, from the IME or from the toolbar
 * API, goes through here so events and paragraph bookkeeping stay consistent. Main thread only.
 */
class BodyText(initial: RichText = RichText(), val env: TextRenderEnv = TextRenderEnv()) {

    val editable: Editable = SpannableStringBuilder()

    private var paragraphs: MutableList<ParagraphStyle> = mutableListOf(ParagraphStyle())

    /** Numbers of numbered paragraphs (0 for others), derived (doc §1.3). */
    var numbers: IntArray = IntArray(1)
        private set

    /** Style for the next typed text when the user toggled a style at a caret; cleared when the caret moves. */
    var typingStyle: CharStyle? = null
        private set

    /** Bumped on any change (text, spans, selection, composition). */
    var version: Long = 0
        private set

    /** Bumped when text or layout-affecting spans change (not on selection moves). */
    var layoutVersion: Long = 0
        private set

    private val editListeners = ArrayList<(TextEditEvent) -> Unit>()
    private val changeListeners = ArrayList<() -> Unit>()

    /** Called after a change the IME didn't make (undo/redo, reset): the IME must restart (doc §4.4). */
    var onExternalChange: (() -> Unit)? = null

    private val watcher = Watcher()
    private val accumulator = EditAccumulator(::slice)
    private var batchDepth = 0
    private var suppressRecording = 0
    private var applyingExact = false
    private var dirty = false
    private var raw: RawChange? = null
    private var expectedCaret = -1

    /**
     * Set by the IME connection before composing calls: `BaseInputConnection` removes the composing
     * span before each replace, so the span alone can't tell that a change is part of a composition.
     */
    internal var imeComposing = false

    init {
        editable.setSpan(watcher, 0, 0, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        reset(initial)
    }

    // ---------------------------------------------------------------- listeners

    fun addEditListener(l: (TextEditEvent) -> Unit) { editListeners += l }
    fun removeEditListener(l: (TextEditEvent) -> Unit) { editListeners -= l }
    fun addChangeListener(l: () -> Unit) { changeListeners += l }
    fun removeChangeListener(l: () -> Unit) { changeListeners -= l }

    // ---------------------------------------------------------------- reading

    val length: Int get() = editable.length

    val paragraphStyles: List<ParagraphStyle> get() = paragraphs.toList()

    /** True when any paragraph is a checked checklist item (no list copy). */
    internal fun hasCheckedItems(): Boolean = paragraphs.any { it.list == ListKind.Checklist && it.checked }

    /** Style of paragraph [index] without copying the list (null when out of range); for per-frame drawing code. */
    internal fun paragraphStyleOrNull(index: Int): ParagraphStyle? = paragraphs.getOrNull(index)

    /** Style of the last paragraph without copying the list. */
    internal fun lastParagraphStyle(): ParagraphStyle = paragraphs.last()

    val selection: TextSelection
        get() {
            val s = Selection.getSelectionStart(editable)
            val e = Selection.getSelectionEnd(editable)
            return if (s < 0) TextSelection(0) else TextSelection(s, if (e < 0) s else e)
        }

    /** Composing region of the IME, or null. */
    val composition: IntRange?
        get() {
            val s = BaseInputConnection.getComposingSpanStart(editable)
            val e = BaseInputConnection.getComposingSpanEnd(editable)
            return if (s < 0 || e < 0 || s == e) null else minOf(s, e) until maxOf(s, e)
        }

    /** Snapshot for saving (doc §5.1). */
    fun toRichText(): RichText = RichTextOps.normalise(
        RichText(editable.toString(), RichTextBridge.readRuns(editable, 0, editable.length), paragraphs.toList()),
    )

    /** Touched slice of `[start, end)` (`RichTextOps.slice` semantics). */
    fun slice(start: Int, end: Int): RichText {
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(s, length)
        val p0 = paragraphIndexAt(s)
        val p1 = p0 + countNewlines(s, e)
        return RichTextOps.normalise(
            RichText(editable.subSequence(s, e).toString(), RichTextBridge.readRuns(editable, s, e), paragraphs.subList(p0, p1 + 1).toList()),
        )
    }

    /**
     * Plain text of `[start, end)` for the system clipboard: list markers are not characters in the body (the editor
     * draws them in the paragraph's leading margin), so a paragraph whose start is inside the range gets its marker
     * prefix, with the body's own item number and the [listMarkerPrefix] rules the .txt export uses. Inline-object
     * placeholders are dropped, like [RichText.plainText].
     */
    fun plainTextWithListMarkers(start: Int, end: Int): String =
        ClipboardText.withListMarkers(editable, paragraphs, numbers, start, end)

    fun paragraphIndexAt(offset: Int): Int = countNewlines(0, offset.coerceIn(0, length))

    fun paragraphStyleAt(offset: Int): ParagraphStyle = paragraphs[paragraphIndexAt(offset)]

    /** `[start, end)` of paragraph [index], excluding its `\n`. */
    fun paragraphRange(index: Int): IntRange {
        var start = 0
        repeat(index) {
            val nl = RichTextBridge.indexOf(editable, '\n', start)
            if (nl < 0) return length until length
            start = nl + 1
        }
        val nl = RichTextBridge.indexOf(editable, '\n', start)
        return start until (if (nl < 0) length else nl)
    }

    /** Style the toolbar should show: the typing style at a caret, else the first selected character's. */
    fun currentCharStyle(): CharStyle {
        val sel = selection
        return if (sel.collapsed) typingStyle ?: styleBefore(sel.min) else RichTextBridge.charStyleAt(editable, sel.min)
    }

    fun currentParagraphStyle(): ParagraphStyle = paragraphStyleAt(selection.min)

    // ---------------------------------------------------------------- selection

    fun setSelection(start: Int, end: Int = start) {
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(0, length)
        if (selection == TextSelection(s, e)) return
        batch { Selection.setSelection(editable, s, e) }
    }

    fun selectAll() = setSelection(0, length)

    // ---------------------------------------------------------------- batching

    /** Groups changes into one notification and (outside composition) one edit event. */
    inline fun <T> batch(block: () -> T): T {
        beginBatch()
        try {
            return block()
        } finally {
            endBatch()
        }
    }

    fun beginBatch() { batchDepth++ }

    fun endBatch() {
        if (batchDepth == 0) return
        batchDepth--
        if (batchDepth == 0) {
            if (composition == null) flushEdit()
            if (dirty) notifyChanged()
        }
    }

    // ---------------------------------------------------------------- editing (recorded)

    /** Replaces `[start, end)` with plain [text] (U+FFFC dropped) and puts the caret after it. */
    fun replace(start: Int, end: Int, text: CharSequence) = batch {
        val s = minOf(start, end).coerceIn(0, length)
        val e = maxOf(start, end).coerceIn(0, length)
        val clean = text.toString().replace(OBJECT_CHAR.toString(), "")
        editable.replace(s, e, clean)
        Selection.setSelection(editable, s + clean.length)
    }

    /** Types [text] over the selection. A lone `\n` behaves like Enter ([insertNewline]). */
    fun commitText(text: CharSequence) {
        if (text.toString() == "\n") insertNewline() else batch {
            if (composition != null || imeComposing) {
                val sel = selection
                replace(sel.min, sel.max, text)
                return@batch
            }
            val sel = selection
            replace(sel.min, sel.max, text)
            applyTypingShortcuts(text)
        }
    }

    /** Typing shortcuts for text the IME just committed (the IME writes to [editable] directly, bypassing [commitText]). */
    internal fun applyImeTypingShortcuts(committed: CharSequence) {
        if (composition != null) return
        batch { applyTypingShortcuts(committed) }
    }

    /** Enter: new paragraph (list continues), or ends the list on an empty item. */
    fun insertNewline() = batch {
        val sel = selection
        val p = paragraphIndexAt(sel.min)
        val style = paragraphs[p]
        val range = paragraphRange(p)
        when {
            sel.collapsed && ParagraphRules.onEnter(style, range.isEmpty()) == ParagraphRules.EnterAction.EndList ->
                changeParagraphs(p, listOf(style.copy(list = ListKind.None, checked = false)))
            sel.collapsed && ParagraphRules.onEnter(style, range.isEmpty()) == ParagraphRules.EnterAction.Outdent ->
                changeParagraphs(p, listOf(style.copy(indent = (style.indent - 1).coerceAtLeast(0), checked = false)))
            else -> {
                // Auto-indent: a plain paragraph keeps its leading spaces/tabs on the new line.
                val indent = if (style.list == ListKind.None && sel.collapsed) leadingIndent(p, sel.min) else ""
                replace(sel.min, sel.max, "\n$indent")
            }
        }
    }

    /** Backspace. At the start of a list item it outdents, then removes the marker. */
    fun deleteBackward() = batch {
        val sel = selection
        if (!sel.collapsed) {
            replace(sel.min, sel.max, "")
            return@batch
        }
        val caret = sel.min
        val p = paragraphIndexAt(caret)
        val range = paragraphRange(p)
        if (caret == range.first && ParagraphRules.backspaceRemovesMarker(paragraphs[p])) {
            val style = paragraphs[p]
            if (style.indent > 0) {
                changeParagraphs(p, listOf(style.copy(indent = style.indent - 1)))
                return@batch
            }
            val conversion = lastConversion
            if (range.isEmpty() && conversion != null && conversion.paragraph == p) {
                // One Backspace right after a marker conversion restores the typed marker text.
                lastConversion = null
                editable.insert(range.first, conversion.marker)
                Selection.setSelection(editable, range.first + conversion.marker.length)
                setParagraphsRaw(p, listOf(style.copy(list = ListKind.None, checked = false)))
                return@batch
            }
            changeParagraphs(p, listOf(style.copy(list = ListKind.None, checked = false)))
            return@batch
        }
        if (caret == 0) return@batch
        val prev = Character.codePointBefore(editable, caret)
        replace(caret - Character.charCount(prev), caret, "")
    }

    fun deleteForward() = batch {
        val sel = selection
        if (!sel.collapsed) {
            replace(sel.min, sel.max, "")
            return@batch
        }
        if (sel.min >= length) return@batch
        val cp = Character.codePointAt(editable, sel.min)
        replace(sel.min, sel.min + Character.charCount(cp), "")
    }

    /**
     * Pastes a rich [fragment] over the selection with `RichTextOps.insertRich` paragraph rules
     * (head keeps its style, middle paragraphs take the fragment's, a plain last one continues).
     */
    fun insertRich(fragment: RichText) = batch {
        val frag = RichTextOps.normalise(fragment)
        val sel = selection
        val s = sel.min
        editable.replace(s, sel.max, frag.text)
        RichTextBridge.setRuns(editable, s, frag.runs, env)
        if (frag.paragraphs.size > 1) {
            val p = paragraphIndexAt(s)
            val head = paragraphs[p]
            val last = frag.paragraphs.last()
            val styles = listOf(head) + frag.paragraphs.subList(1, frag.paragraphs.size - 1) +
                (if (last == ParagraphStyle()) head.copy(checked = false) else last)
            setParagraphsRaw(p, styles)
        }
        Selection.setSelection(editable, s + frag.length)
    }

    fun insertObject(objectId: String) =
        insertRich(RichText(OBJECT_CHAR.toString(), listOf(TextRun(1, currentCharStyle().copy(objectId = objectId)))))

    // ---------------------------------------------------------------- typing shortcuts

    /** A block marker that was just converted, so one Backspace can restore the typed text. */
    private data class TypedConversion(val paragraph: Int, val marker: String)

    private var lastConversion: TypedConversion? = null

    /** Runs after a typed character: line-start markers and, on a space, inline `*bold*`-style markers. */
    private fun applyTypingShortcuts(typed: CharSequence) {
        val sel = selection
        if (!sel.collapsed || typed.isEmpty()) return
        val caret = sel.min
        val p = paragraphIndexAt(caret)
        val range = paragraphRange(p)
        val prefix = editable.subSequence(range.first, caret).toString()
        TypingShortcuts.blockMarker(prefix)?.let { marker ->
            lastConversion = TypedConversion(p, marker.marker)
            editable.delete(range.first, caret)
            Selection.setSelection(editable, range.first)
            setParagraphsRaw(p, listOf(paragraphs[p].copy(list = marker.list, checked = marker.checked)))
            return
        }
        if (typed.length == 1 && typed[0] == ' ') applyInlineShortcut(range)
    }

    /** WhatsApp-style inline markers: `*bold*`, `_italic_`, `~strike~` followed by the typed space. */
    private fun applyInlineShortcut(range: IntRange) {
        val caret = selection.min
        if (caret <= range.first + 1) return
        val line = editable.subSequence(range.first, caret - 1).toString()
        val match = TypingShortcuts.inline(line) ?: return
        val start = range.first + match.start
        val end = range.first + match.end
        val sliceBefore = slice(start, end)
        val transform: (CharStyle) -> CharStyle = when (match.kind) {
            InlineMarker.Kind.Bold -> { cs -> cs.copy(bold = true) }
            InlineMarker.Kind.Italic -> { cs -> cs.copy(italic = true) }
            InlineMarker.Kind.Strikethrough -> { cs -> cs.copy(strikethrough = true) }
        }
        val after = RichTextOps.applyCharStyle(sliceBefore, 0, sliceBefore.length, transform)
        RichTextBridge.setRuns(editable, start, after.runs, env)
        // Remove the closing marker first so the opener's index stays valid.
        editable.delete(end, end + 1)
        editable.delete(start - 1, start)
        // The typed space now sits at end - 1; the caret goes after it so following text is not styled.
        Selection.setSelection(editable, end)
        typingStyle = null
        lastConversion = null
    }

    /** Leading spaces/tabs of paragraph [p] before [caret], for auto-indent. */
    private fun leadingIndent(p: Int, caret: Int): String {
        val range = paragraphRange(p)
        return TypingShortcuts.autoIndent(editable.subSequence(range.first, caret).toString())
    }

    /**
     * Applies [transform] to the selected characters (one [TextEditEvent.CharStyleChange]); at a
     * caret it sets the typing style instead (no event).
     */
    fun applyCharStyle(transform: (CharStyle) -> CharStyle) = batch {
        val sel = selection
        if (sel.collapsed) {
            typingStyle = transform(currentCharStyle()).copy(objectId = null)
            dirty = true
            return@batch
        }
        flushEdit()
        val before = slice(sel.min, sel.max)
        val after = RichTextOps.applyCharStyle(before, 0, before.length, transform)
        if (after.runs == before.runs) return@batch
        RichTextBridge.setRuns(editable, sel.min, after.runs, env)
        emit(TextEditEvent.CharStyleChange(sel.min, before, slice(sel.min, sel.max), sel))
    }

    /** Applies [transform] to every paragraph the selection touches (indent clamped). */
    fun setParagraphStyle(transform: (ParagraphStyle) -> ParagraphStyle) = batch {
        val (first, last) = selectedParagraphs()
        changeParagraphs(first, (first..last).map { transform(paragraphs[it]) })
    }

    /** Toolbar list buttons: `RichTextOps.toggleList` semantics. */
    fun toggleList(kind: ListKind) = batch {
        val (first, last) = selectedParagraphs()
        val allSame = (first..last).all { paragraphs[it].list == kind }
        val target = if (allSame) ListKind.None else kind
        changeParagraphs(first, (first..last).map { i ->
            val p = paragraphs[i]
            if (p.list == target && !p.checked) p else p.copy(list = target, checked = false)
        })
    }

    fun indent() = setParagraphStyle(ParagraphRules::indent)
    fun outdent() = setParagraphStyle(ParagraphRules::outdent)
    fun setAlign(align: TextAlign) = setParagraphStyle { it.copy(align = align) }

    fun toggleChecked(paragraph: Int) = batch {
        val p = paragraphs.getOrNull(paragraph) ?: return@batch
        if (p.list == ListKind.Checklist) changeParagraphs(paragraph, listOf(p.copy(checked = !p.checked)))
    }

    // ---------------------------------------------------------------- history (not recorded)

    /** Re-applies [event] (redo). */
    fun redo(event: TextEditEvent) = applyHistory(event, forward = true)

    /** Reverts [event] (undo). */
    fun undo(event: TextEditEvent) = applyHistory(event, forward = false)

    private fun applyHistory(event: TextEditEvent, forward: Boolean) {
        batch {
            BaseInputConnection.removeComposingSpans(editable)
            flushEdit()
            suppressRecording++
            try {
                when (event) {
                    is TextEditEvent.TextEdit -> {
                        val e = if (forward) event else event.inverse()
                        replaceExact(e.start, e.removed.length, e.inserted)
                        Selection.setSelection(editable, e.selectionAfter.start.coerceIn(0, length), e.selectionAfter.end.coerceIn(0, length))
                    }
                    is TextEditEvent.CharStyleChange -> {
                        RichTextBridge.setRuns(editable, event.start, (if (forward) event.after else event.before).runs, env)
                        Selection.setSelection(editable, event.selection.start.coerceIn(0, length), event.selection.end.coerceIn(0, length))
                    }
                    is TextEditEvent.ParagraphStyleChange -> {
                        setParagraphsRaw(event.firstParagraph, if (forward) event.after else event.before)
                        Selection.setSelection(editable, event.selection.start.coerceIn(0, length), event.selection.end.coerceIn(0, length))
                    }
                }
            } finally {
                suppressRecording--
            }
            accumulator.take()
            typingStyle = null
        }
        onExternalChange?.invoke()
    }

    /** Replaces the whole body (opening a note); no event. */
    fun reset(rt: RichText) {
        val n = RichTextOps.normalise(rt)
        batch {
            BaseInputConnection.removeComposingSpans(editable)
            suppressRecording++
            try {
                for (sp in editable.getSpans(0, editable.length, ParagraphSpan::class.java)) editable.removeSpan(sp)
                replaceExact(0, editable.length, RichText(n.text, n.runs, listOf(ParagraphStyle())))
                paragraphs = n.paragraphs.toMutableList()
                rebuildParagraphSpans(0, paragraphs.size, 0)
                renumber()
                // Replacing the whole text dropped the layout-only pitch span; put it back for the new text.
                linePitchSpan = null
                linePitch?.let { pitch ->
                    linePitchSpan = LinePitchSpan(pitch).also { editable.setSpan(it, 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE) }
                }
                Selection.setSelection(editable, 0)
            } finally {
                suppressRecording--
            }
            accumulator.take()
            typingStyle = null
        }
        onExternalChange?.invoke()
    }

    /** Inline object sizes changed (e.g. an image loaded): re-measure them. */
    fun invalidateObjects() = batch {
        for (sp in editable.getSpans(0, length, InlineObjectSpan::class.java)) {
            val s = editable.getSpanStart(sp)
            val e = editable.getSpanEnd(sp)
            editable.removeSpan(sp)
            editable.setSpan(InlineObjectSpan(sp.objectId, env), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private var linePitch: Float? = null
    private var linePitchSpan: LinePitchSpan? = null

    /**
     * Template rule spacing of the current pages (null = no ruled template). Every line is then laid out a whole
     * number of rules tall. Layout machinery only: not an edit, not saved, no history entry.
     */
    fun setLinePitch(pitch: Float?) {
        if (pitch == linePitch && (pitch == null || linePitchSpan != null)) return
        linePitchSpan?.let { editable.removeSpan(it) }
        linePitch = pitch
        // Also on an empty body: the span grows with the text, so the first typed line is already on pitch.
        linePitchSpan = if (pitch != null) {
            LinePitchSpan(pitch).also { editable.setSpan(it, 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE) }
        } else null
    }

    /** Redraw-only invalidation (colours, page colour, dark mode). */
    fun invalidateDrawing() {
        dirty = true
        if (batchDepth == 0) notifyChanged()
    }

    // ---------------------------------------------------------------- internals

    private fun selectedParagraphs(): Pair<Int, Int> {
        val sel = selection
        val first = paragraphIndexAt(sel.min)
        var last = paragraphIndexAt(sel.max)
        if (sel.max > sel.min && editable[sel.max - 1] == '\n') last = maxOf(first, last - 1)
        return first to last
    }

    /** Recorded paragraph change. */
    private fun changeParagraphs(first: Int, styles: List<ParagraphStyle>) {
        flushEdit()
        val before = paragraphs.subList(first, first + styles.size).toList()
        setParagraphsRaw(first, styles)
        val after = paragraphs.subList(first, first + styles.size).toList()
        if (before != after) emit(TextEditEvent.ParagraphStyleChange(first, before, after, selection))
    }

    private fun setParagraphsRaw(first: Int, styles: List<ParagraphStyle>) {
        val n = minOf(styles.size, paragraphs.size - first)
        if (n <= 0) return
        for (i in 0 until n) {
            paragraphs[first + i] = styles[i].copy(indent = styles[i].indent.coerceIn(0, Limits.MAX_INDENT_LEVEL))
        }
        rebuildParagraphSpans(first, n, paragraphRange(first).first)
        renumber()
        layoutVersion++
        dirty = true
    }

    private fun replaceExact(start: Int, oldLen: Int, fragment: RichText) {
        applyingExact = true
        try {
            editable.replace(start, start + oldLen, fragment.text)
        } finally {
            applyingExact = false
        }
        if (fragment.length > 0) RichTextBridge.setRuns(editable, start, fragment.runs, env)
        setParagraphsRaw(paragraphIndexAt(start), fragment.paragraphs)
    }

    private fun flushEdit() {
        imeComposing = false
        val p = accumulator.take() ?: return
        completeEdit(p)
    }

    private fun completeEdit(p: EditAccumulator.Pending) {
        val inserted = slice(p.start, p.start + p.length)
        if (inserted == p.removed) return
        emit(TextEditEvent.TextEdit(p.start, p.removed, inserted, p.selectionBefore, selection, p.composed))
    }

    private fun emit(event: TextEditEvent) {
        dirty = true
        for (l in editListeners.toList()) l(event)
    }

    private fun notifyChanged() {
        dirty = false
        version++
        for (l in changeListeners.toList()) l()
    }

    private fun countNewlines(start: Int, end: Int): Int {
        var n = 0
        for (i in start until end) if (editable[i] == '\n') n++
        return n
    }

    /** Style of the character before [offset] (after it at 0); `RichTextOps.styleBefore`. */
    private fun styleBefore(offset: Int): CharStyle =
        if (length == 0) CharStyle() else RichTextBridge.charStyleAt(editable, if (offset <= 0) 0 else minOf(offset, length) - 1)

    private fun rebuildParagraphSpans(first: Int, count: Int, startOffset: Int) {
        var pStart = startOffset
        for (i in first until minOf(first + count, paragraphs.size)) {
            val nl = RichTextBridge.indexOf(editable, '\n', pStart)
            val pEnd = if (nl < 0) length else nl + 1
            setParagraphSpan(pStart, pEnd, paragraphs[i])
            if (nl < 0) break
            pStart = pEnd
        }
    }

    private fun setParagraphSpan(start: Int, end: Int, style: ParagraphStyle) {
        val existing = editable.getSpans(start, end, ParagraphSpan::class.java).filter {
            val s = editable.getSpanStart(it)
            val e = editable.getSpanEnd(it)
            (s < end && e > start) || (s == e && s in start..end)
        }
        val wanted = style != ParagraphStyle() && end > start
        if (wanted && existing.size == 1 && existing[0].style == style &&
            editable.getSpanStart(existing[0]) == start && editable.getSpanEnd(existing[0]) == end
        ) return
        for (sp in existing) editable.removeSpan(sp)
        if (wanted) editable.setSpan(ParagraphSpan(style, env), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun renumber() {
        val nums = ParagraphRules.numbering(paragraphs)
        numbers = nums
        if (nums.none { it > 0 }) return
        var pStart = 0
        for (i in paragraphs.indices) {
            val nl = RichTextBridge.indexOf(editable, '\n', pStart)
            if (nums[i] > 0) {
                val end = if (nl < 0) length else nl + 1
                editable.getSpans(pStart, end, ParagraphSpan::class.java)
                    .firstOrNull { editable.getSpanStart(it) == pStart }?.number = nums[i]
            }
            if (nl < 0) break
            pStart = nl + 1
        }
        dirty = true
    }

    private class RawChange(
        val start: Int,
        val removed: Int,
        val inserted: Int,
        val firstParagraph: Int,
        val removedNewlines: Int,
        val style: CharStyle,
    )

    private fun onBefore(start: Int, count: Int, after: Int) {
        val style = typingStyle
            ?: if (count > 0) RichTextBridge.charStyleAt(editable, start) else styleBefore(start)
        raw = RawChange(start, count, after, paragraphIndexAt(start), countNewlines(start, start + count), style.copy(objectId = null))
        if (suppressRecording == 0) {
            accumulator.before(start, count, after, selection, composition != null || imeComposing)?.let(::completeEdit)
        }
    }

    private fun onAfter() {
        val c = raw ?: return
        raw = null
        val insertedNewlines = countNewlines(c.start, c.start + c.inserted)
        paragraphs = ParagraphRules.applyReplace(paragraphs, c.firstParagraph, c.removedNewlines, insertedNewlines).toMutableList()
        if (!applyingExact && c.inserted > 0) {
            RichTextBridge.setStyle(editable, c.start, c.start + c.inserted, c.style, env)
        }
        RichTextBridge.mergeCharStylesAt(editable, c.start, env)
        var pStart = c.start
        while (pStart > 0 && editable[pStart - 1] != '\n') pStart--
        rebuildParagraphSpans(c.firstParagraph, insertedNewlines + 1, pStart)
        if (c.removedNewlines != insertedNewlines || numbers.any { it > 0 }) renumber()
        expectedCaret = c.start + c.inserted
        layoutVersion++
        dirty = true
        if (batchDepth == 0) {
            if (composition == null) flushEdit()
            notifyChanged()
        }
    }

    private fun onSpanEvent(what: Any) {
        if (what === watcher) return
        // The template pitch span is layout machinery: no edit, no dirty flag, no relayout request.
        if (what is LinePitchSpan) return
        if (what === Selection.SELECTION_START || what === Selection.SELECTION_END) {
            val sel = selection
            if (raw == null && !(sel.collapsed && sel.min == expectedCaret)) typingStyle = null
        } else if (what is CharStyleSpan || what is ParagraphSpan || what is InlineObjectSpan) {
            layoutVersion++
        }
        dirty = true
        if (batchDepth == 0 && raw == null) notifyChanged()
    }

    private inner class Watcher : TextWatcher, SpanWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = onBefore(start, count, after)
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable) = onAfter()
        override fun onSpanAdded(text: Spannable, what: Any, start: Int, end: Int) = onSpanEvent(what)
        override fun onSpanRemoved(text: Spannable, what: Any, start: Int, end: Int) = onSpanEvent(what)
        override fun onSpanChanged(text: Spannable, what: Any, ostart: Int, oend: Int, nstart: Int, nend: Int) = onSpanEvent(what)
    }
}

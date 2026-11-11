package org.samesame.notes.core.richtext.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.richtext.BodyText
import org.samesame.notes.core.richtext.BodyTextEngine
import org.samesame.notes.core.richtext.TextLink
import org.samesame.notes.core.richtext.CaretGeometry
import org.samesame.notes.core.richtext.PageTextPainter
import org.samesame.notes.core.richtext.PreparedTextLayout
import org.samesame.notes.core.richtext.RichTextColors
import org.samesame.notes.core.richtext.TextDecorations
import org.samesame.notes.core.richtext.edit.TextEditEvent
import org.samesame.notes.core.richtext.input.InputHost
import org.samesame.notes.core.richtext.input.RichTextInputConnection
import org.samesame.notes.core.richtext.layout.TextPageGeometry
import org.samesame.notes.core.richtext.layout.ViewTransform
import kotlin.math.sqrt

/** Which selection handle is shown / dragged. */
enum class SelectionHandle { Insertion, Start, End }

/**
 * Compose-side state of the body editor: the engine, the doc→view [transform], focus/IME session,
 * caret blink, selection handles, magnifier and floating toolbar requests. Created per screen; the
 * [body] it edits belongs to the session.
 */
@Stable
class RichTextEditorState(
    val body: BodyText,
    geometry: TextPageGeometry,
    private val context: Context,
    /** Text shaped earlier on a background thread ([BodyTextEngine.prepare]); the first frame then draws instead of shaping. */
    prepared: PreparedTextLayout? = null,
) {
    val engine = BodyTextEngine(body, geometry, prepared)

    /** Bumped on every body change; read it in draw code to redraw. */
    var revision by mutableLongStateOf(0L)
        private set

    var geometry: TextPageGeometry
        get() = engine.geometry
        set(value) {
            engine.geometry = value
        }

    /** Document (pu) → view (px). Set by the host on scroll/zoom. */
    var transform by mutableStateOf(ViewTransform())

    var colors: RichTextColors
        get() = engine.colors
        set(value) {
            engine.colors = value
        }

    /** Body hint drawn on an empty note (#26). */
    var hint: String? by mutableStateOf(null)

    /** False in pen and reading modes: no caret, no IME, taps ignored. */
    var enabled by mutableStateOf(true)

    /**
     * View state (not editable): long press still selects a word with handles and the floating toolbar (Copy, Select all),
     * without the keyboard; every tap goes to [onReadOnlyTap] instead (the host decides: link, checkbox, enter edit mode).
     */
    var readOnlySelect by mutableStateOf(false)

    /** Taps while [readOnlySelect] and not [enabled] (view px). */
    var onReadOnlyTap: ((Offset) -> Unit)? = null

    /**
     * Cut from the view state's floating menu (owner decision: the view state offers Cut too). The host switches to edit
     * mode at the selection and cuts there; the body's edit listener records the usual single undo step. Null hides Cut
     * while [readOnlySelect] is on, because the library must not edit a note the host shows read-only.
     */
    var onReadOnlyCut: (() -> Unit)? = null

    /** Gestures run: editable, or the read-only selection of the view state. */
    val acceptsGestures: Boolean get() = enabled || readOnlySelect

    var isFocused by mutableStateOf(false)
        internal set

    internal var caretBlinkOn by mutableStateOf(true)

    var handle: SelectionHandle? by mutableStateOf(null)
        private set

    /** Shown handles: Insertion after a tap, Start/End with a selection, none while typing. */
    var handlesVisible by mutableStateOf(false)
        private set

    /** Magnifier source in view px while a handle is dragged. */
    var magnifierCenter: Offset? by mutableStateOf(null)
        private set

    /** The floating text toolbar should be shown. */
    var toolbarVisible by mutableStateOf(false)
        private set

    /** Handle radius in view px (set from density by the UI). */
    internal var handleRadius = 30f

    val focusRequester = FocusRequester()

    private var inputView: View? = null
    private var reportedSelection = intArrayOf(-1, -1, -1, -1)

    private val changeListener: () -> Unit = {
        revision++
        caretBlinkOn = true
        reportSelectionToIme()
    }
    private val editListener: (TextEditEvent) -> Unit = { e ->
        if (e is TextEditEvent.TextEdit && handle == null) {
            handlesVisible = false
            toolbarVisible = false
        }
    }

    init {
        engine.colors = RichTextColors.Light
        body.addChangeListener(changeListener)
        body.addEditListener(editListener)
        body.onExternalChange = { restartInput() }
    }

    /** Detaches from [body]; call when the screen leaves (the body outlives this state). */
    fun dispose() {
        body.removeChangeListener(changeListener)
        body.removeEditListener(editListener)
        body.onExternalChange = null
    }

    /** "Show links in notes": underline links and open them on tap. */
    var showLinks by mutableStateOf(true)

    /**
     * Called when a link is tapped: a detected link (with [showLinks] on) in edit mode, or any link in the view state and
     * reading mode ([onReadTap]); the host opens it. A stored link run ([TextLink.explicit]) in edit mode places the
     * caret instead; a second tap on it, or a long press, selects the whole link and shows the floating menu, whose
     * extra rows the host adds for [linkInSelection].
     */
    var onLinkClick: ((TextLink) -> Unit)? = null

    /**
     * Paste interception (Files): called instead of inserting when the pasted text is only library file links
     * (`samesame://file/...`, whitespace between them), from the toolbar Paste, the keyboard's paste and a keyboard
     * clipboard chip. The host inserts link runs or images over the current selection. Null pastes the text as is.
     */
    var onPasteFileLinks: ((List<org.samesame.notes.core.model.files.FileLinks.Target>) -> Unit)? = null

    /** The stored link the selection lies in (a caret inside one, or a selection within one), or null. */
    fun linkInSelection(): TextLink? = body.selection.let { engine.storedLinkCovering(it.min, it.max) }

    /** In-note search hits (offsets, `[first, last]`), painted by [decorations]; empty when no search is open. */
    var searchMatches by mutableStateOf<List<IntRange>>(emptyList())

    /** Index of the focused hit in [searchMatches], or -1. */
    var searchCurrent by mutableStateOf(-1)

    fun decorations() = TextDecorations(
        searchMatches = searchMatches,
        searchCurrent = searchCurrent,
        links = showLinks,
        caretVisible = enabled && isFocused && caretBlinkOn && handle == null,
        selectionVisible = true,
        // #27: the hint goes away as soon as the body has focus.
        hint = if (isFocused) null else hint,
    )

    /**
     * The body text split for the canvas' page layers (see [PageTextPainter]): one decorations snapshot per frame, the
     * static glyph pass recorded per page and replayed while scrolling, selection / search / caret drawn live.
     */
    val pagePainter: PageTextPainter = object : PageTextPainter {
        private var frame = TextDecorations()

        override fun beginFrame(): Long {
            @Suppress("UNUSED_VARIABLE") val rev = revision // observe: any body change redraws the canvas
            frame = decorations()
            return engine.staticVersion(frame)
        }

        override fun drawUnder(canvas: android.graphics.Canvas, pageIndex: Int) =
            engine.drawPageUnder(canvas, pageIndex, frame, inPageSpace = true)

        override fun drawStatic(canvas: android.graphics.Canvas, pageIndex: Int) =
            engine.drawPageStatic(canvas, pageIndex, frame, inPageSpace = true)

        override fun drawOver(canvas: android.graphics.Canvas, pageIndex: Int) =
            engine.drawPageOver(canvas, pageIndex, frame, inPageSpace = true)
    }

    // ---------------------------------------------------------------- IME

    private var keyboardWanted = false

    internal fun attachInput(view: View) {
        inputView = view
        reportedSelection = intArrayOf(-1, -1, -1, -1)
        if (keyboardWanted) view.post { imm.showSoftInput(view, 0) }
    }

    internal fun detachInput(view: View) {
        if (inputView === view) inputView = null
    }

    internal fun createInputConnection(view: View, outAttrs: EditorInfo): InputConnection {
        RichTextInputConnection.configure(outAttrs, body)
        val sel = body.selection
        reportedSelection = intArrayOf(sel.start, sel.end, -1, -1)
        return RichTextInputConnection(view, body, inputHost)
    }

    private val inputHost = object : InputHost {
        override fun cut() = this@RichTextEditorState.cut()
        override fun copy() = this@RichTextEditorState.copy()
        override fun paste() = this@RichTextEditorState.paste()
        override fun pasteText(text: String): Boolean = interceptFileLinks(text)
        override fun onImeChanged() = Unit
    }

    private val imm: InputMethodManager
        get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    /** Focuses the body without showing the keyboard: the caret shows, the keyboard waits for a tap (#26). */
    fun requestFocus() {
        if (enabled && !isFocused) focusRequester.requestFocus()
    }

    /** Focuses the body and shows the keyboard. */
    fun requestInput() {
        if (!enabled) return
        keyboardWanted = true
        if (!isFocused) focusRequester.requestFocus()
        inputView?.let { v -> v.post { imm.showSoftInput(v, 0) } }
    }

    fun hideKeyboard() {
        keyboardWanted = false
        inputView?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    /** After undo/redo or any change the IME didn't make (doc §4.4). */
    fun restartInput() {
        inputView?.let { imm.restartInput(it) }
    }

    private fun reportSelectionToIme() {
        val v = inputView ?: return
        val sel = body.selection
        val comp = body.composition
        val now = intArrayOf(sel.start, sel.end, comp?.first ?: -1, comp?.let { it.last + 1 } ?: -1)
        if (now.contentEquals(reportedSelection)) return
        reportedSelection = now
        imm.updateSelection(v, now[0], now[1], now[2], now[3])
    }

    // ---------------------------------------------------------------- geometry helpers (view px)

    private fun toDoc(p: Offset) = transform.viewToDoc(p.x, p.y)

    fun caretInView(offset: Int): Pair<Offset, Offset> {
        val c: CaretGeometry = engine.textCaret(offset)
        val t = transform
        return Offset(t.docToViewX(c.x), t.docToViewY(c.top)) to Offset(t.docToViewX(c.x), t.docToViewY(c.bottom))
    }

    /** Centre of a handle's body in view px. */
    internal fun handleCenter(h: SelectionHandle): Offset {
        val sel = body.selection
        val r = handleRadius
        return when (h) {
            SelectionHandle.Insertion -> caretInView(sel.min).second.let { Offset(it.x, it.y + r * sqrt(2f)) }
            SelectionHandle.Start -> caretInView(sel.min).second.let { Offset(it.x - r, it.y + r) }
            SelectionHandle.End -> caretInView(sel.max).second.let { Offset(it.x + r, it.y + r) }
        }
    }

    internal fun visibleHandles(): List<SelectionHandle> {
        if (!handlesVisible || !acceptsGestures) return emptyList()
        return if (body.selection.collapsed) listOf(SelectionHandle.Insertion) else listOf(SelectionHandle.Start, SelectionHandle.End)
    }

    /** Handle under a view point (touch target = 2 × radius around its body). */
    fun handleAt(p: Offset): SelectionHandle? = visibleHandles().minByOrNull { (handleCenter(it) - p).getDistance() }
        ?.takeIf { (handleCenter(it) - p).getDistance() <= handleRadius * 2f }

    /** Selection bounds in view px (anchor for the floating toolbar). */
    fun selectionRectInView(): Rect {
        val sel = body.selection
        val r = engine.selectionBounds(sel.min, sel.max)
        val t = transform
        return Rect(t.docToViewX(r.left), t.docToViewY(r.top), t.docToViewX(r.right), t.docToViewY(r.bottom))
    }

    // ---------------------------------------------------------------- gestures (view px)

    private var lastTapOffset = -1
    private var dragTouchOffset = Offset.Zero
    private var longPressAnchor: IntRange? = null

    fun onTap(p: Offset) {
        if (!enabled) {
            if (readOnlySelect) onReadOnlyTap?.invoke(p)
            return
        }
        val d = toDoc(p)
        engine.checkboxAt(d.x, d.y)?.let {
            body.toggleChecked(it)
            return
        }
        val link = engine.linkAt(d.x, d.y, detected = showLinks)
        if (link != null && !link.explicit && link.kind != TextLink.Kind.FileLink) {
            val open = onLinkClick
            if (open != null) {
                open(link)
                return
            }
        }
        if (link != null && link.explicit) {
            // Edit mode: the first tap on a stored link places the caret; a tap while the caret is already in it (or the
            // link is selected) selects the whole link and shows the menu (Open, Copy link, Edit label, ...).
            val sel = body.selection
            if (isFocused && handlesVisible && sel.min >= link.start && sel.max <= link.end) {
                selectRange(link.start, link.end)
                lastTapOffset = -1
                return
            }
        }
        val offset = engine.hitTestForTap(d.x, d.y)
        val sel = body.selection
        if (sel.collapsed && sel.min == offset && handlesVisible && isFocused) {
            toolbarVisible = !toolbarVisible
        } else {
            body.setSelection(offset)
            toolbarVisible = false
        }
        lastTapOffset = offset
        handlesVisible = true
        requestInput()
    }

    /**
     * Reading mode / view state tap: only links react (no caret, no keyboard, no checkbox toggling). Stored link runs and
     * file links always react; detected links only with [showLinks]. True when a link was opened.
     */
    fun onReadTap(p: Offset): Boolean {
        val d = toDoc(p)
        val link = engine.linkAt(d.x, d.y, detected = showLinks) ?: return false
        val open = onLinkClick ?: return false
        open(link)
        return true
    }

    fun onDoubleTap(p: Offset) {
        if (!enabled) {
            if (readOnlySelect) onReadOnlyTap?.invoke(p)
            return
        }
        selectWordAt(p)
    }

    fun onLongPress(p: Offset) {
        if (!acceptsGestures) return
        val d = toDoc(p)
        val link = engine.linkAt(d.x, d.y, detected = false)?.takeIf { it.explicit }
        if (link != null) {
            // A long press on a stored link selects the whole link (its menu rows come from the host).
            body.setSelection(link.start, link.end)
            handlesVisible = true
            toolbarVisible = true
            longPressAnchor = link.start until link.end
            requestInput()
            return
        }
        selectWordAt(p)
        longPressAnchor = body.selection.let { it.min until it.max }
    }

    fun onLongPressDrag(p: Offset) {
        val anchor = longPressAnchor ?: return
        val d = toDoc(p)
        val o = engine.hitTest(d.x, d.y)
        val word = engine.wordAt(o)
        val start = minOf(anchor.first, if (word.isEmpty()) o else word.first)
        val end = maxOf(anchor.last + 1, if (word.isEmpty()) o else word.last + 1)
        body.setSelection(start, end)
        magnifierCenter = caretCenter(o)
        toolbarVisible = false
        handlesVisible = true
    }

    fun onLongPressEnd() {
        longPressAnchor = null
        magnifierCenter = null
        toolbarVisible = true
    }

    private fun selectWordAt(p: Offset) {
        val d = toDoc(p)
        val o = engine.hitTest(d.x, d.y)
        val w = engine.wordAt(o)
        if (w.isEmpty()) body.setSelection(o) else body.setSelection(w.first, w.last + 1)
        handlesVisible = true
        toolbarVisible = true
        requestInput()
    }

    fun beginHandleDrag(h: SelectionHandle, p: Offset) {
        handle = h
        val sel = body.selection
        val anchor = caretInView(if (h == SelectionHandle.End) sel.max else sel.min)
        // Keep the finger's offset to the caret's mid-line so the text under the finger stays visible.
        val mid = Offset(anchor.first.x, (anchor.first.y + anchor.second.y) / 2f)
        dragTouchOffset = p - mid
        toolbarVisible = false
        magnifierCenter = mid
    }

    fun dragHandle(p: Offset) {
        val h = handle ?: return
        val target = p - dragTouchOffset
        val d = toDoc(target)
        val o = engine.hitTest(d.x, d.y)
        val sel = body.selection
        when (h) {
            SelectionHandle.Insertion -> body.setSelection(o)
            SelectionHandle.Start -> body.setSelection(minOf(o, sel.max - 1).coerceAtLeast(0), sel.max)
            SelectionHandle.End -> body.setSelection(sel.min, maxOf(o, sel.min + 1).coerceAtMost(body.length))
        }
        val moved = when (h) {
            SelectionHandle.End -> body.selection.max
            else -> body.selection.min
        }
        magnifierCenter = caretCenter(moved).let { Offset(target.x, it.y) }
    }

    fun endHandleDrag() {
        handle = null
        magnifierCenter = null
        toolbarVisible = true
    }

    private fun caretCenter(offset: Int): Offset {
        val (a, b) = caretInView(offset)
        return Offset(a.x, (a.y + b.y) / 2f)
    }

    /** Hides handles and toolbar (e.g. on scroll start or mode switch). */
    fun dismissSelectionUi() {
        handlesVisible = false
        toolbarVisible = false
    }

    // ---------------------------------------------------------------- clipboard

    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * The text is sensitive (a locked note): copies are flagged so the system clipboard preview and keyboards with a
     * clipboard history do not show or keep them (security review I5 / L2).
     */
    var sensitive: Boolean = false

    fun copy() {
        val sel = body.selection
        if (sel.collapsed) return
        val rich = body.slice(sel.min, sel.max)
        // Bug 14: the markers are drawn in the leading margin, not stored as characters, so the slice's plain text has
        // no "1. "/"• "; the system clipboard gets them (shared with the .txt export) while [RichClipboard] keeps the
        // styled slice for in-app paste.
        val plain = body.plainTextWithListMarkers(sel.min, sel.max)
        RichClipboard.put(rich, plain)
        val clip = ClipData.newPlainText("note", plain)
        if (sensitive) {
            // ClipDescription.EXTRA_IS_SENSITIVE (API 33); the same key is honoured by keyboards on older releases.
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        }
        clipboard.setPrimaryClip(clip)
        toolbarVisible = false
    }

    fun cut() {
        val sel = body.selection
        if (sel.collapsed) return
        copy()
        body.replace(sel.min, sel.max, "")
        handlesVisible = false
    }

    fun paste() {
        val clip = clipboard.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        val rich = RichClipboard.richFor(text)
        when {
            // An in-app copy keeps its runs (a copied link run stays a link).
            rich != null -> body.insertRich(rich)
            interceptFileLinks(text) -> Unit
            else -> body.commitText(text)
        }
        toolbarVisible = false
        handlesVisible = false
    }

    /** Hands [text] to [onPasteFileLinks] when it is only file links; true when it was handled. */
    private fun interceptFileLinks(text: String): Boolean {
        val handler = onPasteFileLinks ?: return false
        if (!text.contains(org.samesame.notes.core.model.files.FileLinks.SCHEME, ignoreCase = true)) return false
        val targets = org.samesame.notes.core.model.files.FileLinks.parseLinksOnly(text) ?: return false
        handler(targets)
        return true
    }

    /**
     * Selects [start, end) with handles and the floating toolbar, as a long press would, and focuses the body without
     * asking for the keyboard (used for an anchored object: a plain `body.setSelection` shows no toolbar).
     */
    fun selectRange(start: Int, end: Int) {
        val a = start.coerceIn(0, body.length)
        val b = end.coerceIn(a, body.length)
        body.setSelection(a, b)
        handlesVisible = true
        toolbarVisible = true
        requestFocus()
    }

    fun selectAll() {
        body.selectAll()
        handlesVisible = true
        toolbarVisible = true
    }

    fun canPaste(): Boolean = clipboard.hasPrimaryClip()

    internal fun hasSelection(): Boolean = !body.selection.collapsed
}

/** `ClipDescription.EXTRA_IS_SENSITIVE`, spelled out so it also works below API 33. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

/** Keeps rich styling for in-app copy/paste; the system clipboard gets plain text. */
internal object RichClipboard {
    private var rich: RichText? = null
    private var plain: String? = null

    /** [plainText] is exactly what went to the system clipboard (list markers included). */
    fun put(rt: RichText, plainText: String) {
        rich = rt
        plain = plainText
    }

    fun richFor(text: String): RichText? = rich?.takeIf { plain == text || it.plainText() == text }
}

/** Remembers a [RichTextEditorState] for [body]; disposes it when it leaves composition. */
@Composable
fun rememberRichTextEditorState(
    body: BodyText,
    geometry: TextPageGeometry,
    prepared: PreparedTextLayout? = null,
): RichTextEditorState {
    val context = LocalContext.current
    val state = remember(body) { RichTextEditorState(body, geometry, context.applicationContext, prepared) }
    state.geometry = geometry
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}

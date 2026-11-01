package org.samesame.notes.feature.composer.session

import org.samesame.notes.core.model.document.frame
import androidx.compose.ui.geometry.Offset
import androidx.ink.geometry.AffineTransform
import androidx.ink.strokes.Stroke
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.samesame.notes.core.data.repository.EditorPreferencesRepository
import org.samesame.notes.core.document.NoteSnapshot
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.floating
import org.samesame.notes.core.ink.page.newObjectElement
import org.samesame.notes.core.ink.page.withFloating
import org.samesame.notes.core.model.document.ObjectLimits
import org.samesame.notes.core.model.document.TextBoxObject
import org.samesame.notes.core.model.document.TemplateRef
import org.samesame.notes.core.model.document.TextWrap
import org.samesame.notes.core.model.document.PaintingObject
import org.samesame.notes.core.model.document.VoiceObject
import org.samesame.notes.core.model.document.fittedToWidth
import org.samesame.notes.core.model.document.isFittedToWidth
import org.samesame.notes.core.model.document.isImage
import org.samesame.notes.core.model.document.isResizable
import org.samesame.notes.core.model.document.resizedToDefault
import org.samesame.notes.core.model.document.supportsWrap
import org.samesame.notes.core.model.document.withWrap
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.ink.page.box
import org.samesame.notes.core.ink.tools.StrokeTransforms
import org.samesame.notes.core.ink.tools.randomElementId
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.document.FloatingObject
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.NoteObject
import org.samesame.notes.core.model.document.NoteRecording
import org.samesame.notes.core.model.document.NoteStyle
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.model.document.SizePu
import org.samesame.notes.core.model.document.ViewState
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.feature.composer.history.AddElements
import org.samesame.notes.feature.composer.voice.VoiceRecording
import org.samesame.notes.feature.composer.history.BodyTextTarget
import org.samesame.notes.feature.composer.history.CharStyleChange
import org.samesame.notes.feature.composer.history.EditTarget
import org.samesame.notes.feature.composer.history.Group
import org.samesame.notes.feature.composer.history.IndexedElement
import org.samesame.notes.feature.composer.history.ObjectChange
import org.samesame.notes.feature.composer.history.PageListChange
import org.samesame.notes.feature.composer.history.PageSpecChange
import org.samesame.notes.feature.composer.history.ParagraphStyleChange
import org.samesame.notes.feature.composer.history.ReplaceElements
import org.samesame.notes.feature.composer.history.Reorder
import org.samesame.notes.feature.composer.history.RecordingsChange
import org.samesame.notes.feature.composer.history.TextEdit
import org.samesame.notes.feature.composer.history.TextSel
import org.samesame.notes.feature.composer.history.UndoManager
import org.samesame.notes.feature.composer.history.PagePasteChange
import org.samesame.notes.feature.composer.history.UndoState
import org.samesame.notes.feature.composer.toolbar.EditorMode
import org.samesame.notes.feature.composer.toolbar.ToolbarIntent
import org.samesame.notes.feature.composer.toolbar.ToolbarState

/**
 * Top-left corner of a new text box (page space). the reference (ContextMenuAddTextBox): at the paste position when there is a
 * valid one, its x held at least one margin in from the left edge; otherwise one margin in from the top-left corner.
 */
internal fun textBoxInsertTopLeft(pastePu: Offset?, marginPu: Float = ObjectLimits.TEXT_BOX_MARGIN_PU): Offset =
    if (pastePu == null) Offset(marginPu, marginPu) else Offset(pastePu.x.coerceAtLeast(marginPu), pastePu.y)

/** `chrome` flow: everything the bars and menus need, nothing the canvas redraws on. */
data class ChromeState(
    val title: String = "",
    val toolbar: ToolbarState = ToolbarState(),
    val undo: UndoState = UndoState(),
    val hasSelection: Boolean = false,
    val canPaste: Boolean = false,
)

/** `document` flow: layout, page specs, style and the note-level recording list (the canvas relayouts on this; page edits are observed through `PageSource`). */
data class DocumentUiState(
    val layout: PageLayout,
    val style: NoteStyle,
    val pages: List<PageSpec>,
    val recordings: List<VoiceRecording> = emptyList(),
)

/**
 * Everything about one open note, in plain Kotlin (no Android types): document structure, page layers, history,
 * tools, selection, autosave. The ViewModel owns the scope and the repository lambdas; JVM tests pass fakes.
 *
 * Threading: all methods are called on [scope]'s (main) thread; writes happen on [io].
 */
class EditorSession(
    val noteId: String,
    initialDocument: NoteDocument,
    private val scope: CoroutineScope,
    io: CoroutineDispatcher,
    pageLoader: suspend (pageId: String) -> PageContent,
    saveSink: suspend (NoteSnapshot) -> Unit,
    packContainer: suspend (close: Boolean) -> Unit,
    /** Reads `media/<name>` bytes (image objects on reload). */
    private val mediaLoader: suspend (path: String) -> ByteArray? = { null },
    /** Opening mode: Text (edit) for a note the FAB just created, Reading for an existing note. */
    initialMode: EditorMode = EditorMode.Text,
    /** Writes image bytes into the bundle and returns the stored `media/<name>` path. */
    private val mediaSink: suspend (bytes: ByteArray, extension: String) -> String = { _, _ -> "" },
    prefs: EditorPreferencesRepository,
    val clipboard: EditorClipboard = EditorClipboard(),
    clock: () -> Long = System::currentTimeMillis,
    maxLoadedPages: Int = PageStore.DEFAULT_MAX_LOADED,
    undoLimit: Int = Limits.MAX_UNDO_STEPS,
    autosaveDebounceMs: Long = Autosaver.DEBOUNCE_MS,
    autosaveMaxIntervalMs: Long = Autosaver.MAX_INTERVAL_MS,
) : EditTarget {

    val holder = DocumentHolder(initialDocument)
    val pages = PageStore(scope, pageLoader, maxLoadedPages)
    val history = UndoManager(limit = undoLimit, clock = clock)
    val selection = SelectionController()
    val toolController = ToolController(
        scope,
        prefs,
        initial = ToolbarState(mode = initialMode, viewMode = initialMode == EditorMode.Reading),
        onModeOrToolChanged = { history.breakBurst() },
    )

    private val autosaver = Autosaver(
        scope = scope,
        io = io,
        source = object : SaveSource {
            override fun isDirty() = holder.isDirty || pages.hasDirty()
            override fun takeSnapshot(): PendingSave? {
                if (!isDirty()) return null
                val (doc, rev) = holder.snapshot()
                val dirty = pages.dirtyPages()
                return PendingSave(NoteSnapshot(doc, dirty)) {
                    holder.markSaved(rev)
                    pages.markSaved(dirty)
                }
            }
        },
        write = saveSink,
        packContainer = packContainer,
        clock = clock,
        debounceMs = autosaveDebounceMs,
        maxIntervalMs = autosaveMaxIntervalMs,
    )

    /** Save failures for a snackbar; the dirty flags are kept and the save retries with backoff. */
    val saveErrors: SharedFlow<Throwable> get() = autosaver.errors

    private val fallbackBody = InMemoryBodyText(initialDocument.body)
    private var boundBody: BodyTextTarget = fallbackBody
    private var applyingHistory = false
    private val historyMutex = Mutex()

    init {
        holder.bindBody(boundBody)
        pages.onDirty = autosaver::onEdit
        initialDocument.pages.firstOrNull()?.let { pages.request(it.id) }
    }

    // ------------------------------------------------------------------------------------------ state flows

    val tools: StateFlow<ToolsState> get() = toolController.tools
    val selectionState: StateFlow<SelectionState> get() = selection.state

    val chrome: StateFlow<ChromeState> = combine(
        holder.structure, toolController.toolbar, history.state, selection.state,
    ) { doc, toolbar, undoState, sel ->
        ChromeState(doc.title, toolbar, undoState, hasSelection = !sel.isEmpty, canPaste = clipboard.hasContent)
    }.stateIn(
        scope, SharingStarted.Eagerly,
        ChromeState(initialDocument.title, toolController.toolbar.value, history.state.value),
    )

    val document: StateFlow<DocumentUiState> = holder.structure.map { doc ->
        DocumentUiState(doc.layout, doc.style, doc.pages, recordingRows(doc))
    }.stateIn(
        scope, SharingStarted.Eagerly,
        DocumentUiState(initialDocument.layout, initialDocument.style, initialDocument.pages, recordingRows(initialDocument)),
    )

    private fun recordingRows(doc: NoteDocument): List<VoiceRecording> = doc.recordings.map {
        VoiceRecording(it.id, it.media, it.durationMs, it.name, it.createdAtMs)
    }

    fun dispatch(intent: ToolbarIntent): ToolbarState = toolController.dispatch(intent)

    // ------------------------------------------------------------------------------------------ text hooks (WP-J / WP-F)

    /** The body text the session currently edits through (the in-memory fallback until the engine binds). */
    override val body: BodyTextTarget get() = boundBody

    /** Initial body for the engine to load. */
    val initialBody: RichText get() = holder.current.body

    /** Binds the text engine's body; pass null to fall back to the in-memory body. */
    fun bindBody(target: BodyTextTarget?) {
        boundBody = target ?: fallbackBody
        holder.bindBody(boundBody)
    }

    /** From the engine's `TextEdit` event: records history (coalescing typing) and marks the body dirty. */
    fun onTextEdit(
        start: Int, removed: RichText, inserted: RichText,
        selectionBefore: TextSel, selectionAfter: TextSel, composed: Boolean = false,
    ) {
        if (!applyingHistory) history.record(TextEdit(start, removed, inserted, selectionBefore, selectionAfter, composed))
        bodyChanged()
    }

    fun onCharStyleChange(start: Int, before: RichText, after: RichText, selection: TextSel) {
        if (!applyingHistory) history.record(CharStyleChange(start, before, after, selection))
        bodyChanged()
    }

    fun onParagraphStyleChange(first: Int, before: List<ParagraphStyle>, after: List<ParagraphStyle>, selection: TextSel) {
        if (!applyingHistory) history.record(ParagraphStyleChange(first, before, after, selection))
        bodyChanged()
    }

    /** Cursor jump, pending-style toggle, focus change: the next keystroke starts a new undo step. */
    fun breakTypingBurst() = history.breakBurst()

    /** Several edits as one undo step (e.g. text that appends pages). */
    fun <T> group(block: () -> T): T = history.group(block)

    fun changeInlineObject(id: String, after: NoteObject?) {
        val before = holder.inlineObject(id)
        if (before == after) return
        holder.setInlineObject(id, after)
        history.record(ObjectChange(id, before, after))
        autosaver.onEdit()
    }

    private fun bodyChanged() {
        holder.onBodyChanged()
        autosaver.onEdit()
    }

    // ------------------------------------------------------------------------------------------ title / view / layout

    fun setTitle(title: String) {
        if (title == holder.current.title) return
        holder.setTitle(title)
        autosaver.onEdit()
    }

    /** Saved with the next save/flush; does not trigger one by itself (scrolling is not an edit). */
    fun setViewState(v: ViewState?) = holder.setViewState(v)

    fun setLayout(layout: PageLayout) {
        holder.setLayout(layout)
        autosaver.onEdit()
    }

    fun setStyle(style: NoteStyle) {
        holder.setStyle(style)
        autosaver.onEdit()
    }

    // ------------------------------------------------------------------------------------------ ink

    fun content(pageId: String): PageContent? = pages.content(pageId)

    /** A finished live stroke on [pageId] (page space). Appended on top and recorded. */
    fun addStroke(pageId: String, stroke: Stroke): Boolean = addElements(pageId, listOf(InkElement(randomElementId(), stroke)))

    /** Media bytes for an image object (canvas drawing); null when missing. */
    suspend fun loadMediaBytes(path: String): ByteArray? = mediaLoader(path)

    /**
     * Writes image [bytes] into the bundle ([extension] e.g. "png") and puts a floating image on
     * [pageId] centred at [centerPu] (page space). One command.
     */
    suspend fun insertImage(pageId: String, bytes: ByteArray, extension: String, widthPu: Float, heightPu: Float, centerPu: Offset): Boolean {
        val media = mediaSink(bytes, extension)
        if (media.isEmpty()) return false
        val rect = RectPu(centerPu.x - widthPu / 2f, centerPu.y - heightPu / 2f, centerPu.x + widthPu / 2f, centerPu.y + heightPu / 2f)
        val objectId = randomElementId()
        // A new image goes to the bottom layer (below text and ink) so the user can write on top of it.
        val json = FloatingObject(ImageObject(objectId.value.toString(), media, widthPu, heightPu), rect, wrap = TextWrap.Behind).toJson()
        return editElements(pageId) { listOf(ObjectElement(objectId, json, box(rect.left, rect.top, rect.right, rect.bottom))) + it }
    }

    /** Bytes of a bundle file (a drawing's stroke payload); null when missing. */
    suspend fun loadDrawingInk(path: String): ByteArray? = mediaLoader(path)

    /**
     * Inserts a finished drawing (Insert > Drawing): [thumbnail] (PNG) and [ink] (stroke payload) are stored in the bundle and
     * a [PaintingObject] covering [content] of the sheet goes on [pageId], centred at [centerPu] (kept inside the page) and
     * scaled down to at most 90% of the page width. One undo step; the new object is selected (the reference selects it).
     * Returns its element id, null when a file cannot be stored.
     */
    suspend fun insertDrawing(pageId: String, thumbnail: ByteArray, ink: ByteArray, content: RectPu, centerPu: Offset, pageSize: SizePu): ElementId? {
        val thumbMedia = mediaSink(thumbnail, "png")
        val inkMedia = mediaSink(ink, Limits.DRAWING_INK_EXTENSION)
        if (thumbMedia.isEmpty() || inkMedia.isEmpty()) return null
        val k = minOf(1f, pageSize.width * ObjectLimits.DRAWING_MAX_WIDTH_FRACTION / content.width, pageSize.height / content.height)
        val w = content.width * k; val h = content.height * k
        val cx = centerPu.x.coerceIn(w / 2f, (pageSize.width - w / 2f).coerceAtLeast(w / 2f))
        val cy = centerPu.y.coerceIn(h / 2f, (pageSize.height - h / 2f).coerceAtLeast(h / 2f))
        val id = randomElementId()
        val rect = RectPu(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val el = newObjectElement(id, FloatingObject(PaintingObject(id.value.toString(), thumbMedia, inkMedia, content), rect))
        if (!addElements(pageId, listOf(el))) return null
        selection.select(pageId, listOf(el))
        return id
    }

    /**
     * Re-edit result: the object [id] keeps its id, scale and place; the sheet region its thumbnail covers moves with the new
     * [content] (the strokes stay put on the page, a longer drawing grows around them). One undo step.
     */
    suspend fun replaceDrawing(pageId: String, id: ElementId, thumbnail: ByteArray, ink: ByteArray, content: RectPu): Boolean {
        val old = (pages.content(pageId)?.elements?.firstOrNull { it.id == id } as? ObjectElement)?.floating() ?: return false
        val oldObj = old.obj as? PaintingObject ?: return false
        val thumbMedia = mediaSink(thumbnail, "png")
        val inkMedia = mediaSink(ink, Limits.DRAWING_INK_EXTENSION)
        if (thumbMedia.isEmpty() || inkMedia.isEmpty()) return false
        val sx = old.rect.width / oldObj.content.width.coerceAtLeast(1f)
        val sy = old.rect.height / oldObj.content.height.coerceAtLeast(1f)
        // Shift of the content centre (sheet space -> object space), turned by the object's rotation.
        val dx = ((content.left + content.right) - (oldObj.content.left + oldObj.content.right)) / 2f * sx
        val dy = ((content.top + content.bottom) - (oldObj.content.top + oldObj.content.bottom)) / 2f * sy
        val a = Math.toRadians(old.rotationDeg.toDouble())
        val rx = (dx * kotlin.math.cos(a) - dy * kotlin.math.sin(a)).toFloat()
        val ry = (dx * kotlin.math.sin(a) + dy * kotlin.math.cos(a)).toFloat()
        val cx = (old.rect.left + old.rect.right) / 2f + rx
        val cy = (old.rect.top + old.rect.bottom) / 2f + ry
        val w = content.width * sx; val h = content.height * sy
        val updated = old.copy(
            obj = oldObj.copy(media = thumbMedia, ink = inkMedia, content = content),
            rect = RectPu(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f),
        )
        val changed = editElements(pageId) { list -> list.map { e -> if (e.id == id && e is ObjectElement) e.withFloating(updated) else e } }
        if (changed) selection.select(pageId, listOfNotNull(pages.content(pageId)?.elements?.firstOrNull { it.id == id }))
        return changed
    }

    /**
     * Inserts an empty text box whose top-left corner is [topLeftPu] (page space) and selects it. One command. Returns its element id.
     * The caller picks the corner with [textBoxInsertTopLeft].
     */
    fun insertTextBox(pageId: String, topLeftPu: Offset, text: RichText = RichText()): ElementId? {
        val w = ObjectLimits.DEFAULT_TEXT_BOX_WIDTH_PU; val h = ObjectLimits.DEFAULT_TEXT_BOX_HEIGHT_PU
        val id = randomElementId()
        val rect = RectPu(topLeftPu.x, topLeftPu.y, topLeftPu.x + w, topLeftPu.y + h)
        val el = newObjectElement(id, FloatingObject(TextBoxObject(id.value.toString(), text), rect))
        if (!addElements(pageId, listOf(el))) return null
        selection.select(pageId, listOf(el))
        return id
    }

    /**
     * Inserts a voice recording object ([media] already stored in the bundle) at [centerPu]; selects nothing.
     */
    fun insertVoice(pageId: String, media: String, durationMs: Long, centerPu: Offset): ElementId? {
        val w = ObjectLimits.VOICE_WIDTH_PU; val h = ObjectLimits.VOICE_HEIGHT_PU
        val id = randomElementId()
        val rect = RectPu(centerPu.x - w / 2f, centerPu.y - h / 2f, centerPu.x + w / 2f, centerPu.y + h / 2f)
        val el = newObjectElement(id, FloatingObject(VoiceObject(id.value.toString(), media, durationMs), rect))
        return if (addElements(pageId, listOf(el))) id else null
    }

    /** The note's note-level recordings in recording order: the "Recorded audio" list. */
    fun voiceRecordings(): List<VoiceRecording> = recordingRows(holder.current)

    /**
     * Appends a finished recording ([media] already stored in the bundle) to the note's recording list as one undo
     * step.
     * Returns its id.
     */
    fun addVoiceRecording(
        media: String,
        name: String = "",
        createdAtMs: Long = 0L,
        elapsedMs: Long = 0L,
        displayDuration: String = "",
    ): String {
        val id = java.util.UUID.randomUUID().toString()
        val before = holder.current.recordings
        val stored = name.ifBlank { org.samesame.notes.feature.composer.voice.VoiceFormat.nextName(before.map { it.name }) }
        val after = before + NoteRecording(id, media, stored, createdAtMs, elapsedMs, displayDuration)
        holder.setRecordings(after)
        history.record(RecordingsChange(before, after))
        autosaver.onEdit()
        return id
    }

    /** Renames one recording (the list's rename dialog); not an undo step, like [setTitle]. */
    fun renameVoiceRecording(id: String, name: String) {
        val before = holder.current.recordings
        val after = before.map { if (it.id == id) it.copy(name = name) else it }
        if (after == before) return
        holder.setRecordings(after)
        autosaver.onEdit()
    }

    /** Removes the recordings [ids] (the list's Delete) as one undo step; the media stays in the bundle. */
    fun deleteVoiceRecordings(ids: List<String>) {
        val idSet = ids.toHashSet()
        val before = holder.current.recordings
        val after = before.filterNot { it.id in idSet }
        if (after == before) return
        holder.setRecordings(after)
        history.record(RecordingsChange(before, after))
        autosaver.onEdit()
    }

    /** Writes [bytes] into the bundle and returns the `media/<name>` path (empty on failure). */
    suspend fun storeMedia(bytes: ByteArray, extension: String): String = mediaSink(bytes, extension)

    /**
     * Replaces the text of a text box (the end of an editing session) as one command; no-op when unchanged.
     * The box grows to [minHeightPu] when the text needs more room; with [fitHeight] (a box that sizes itself to its
     * text) its height becomes exactly [minHeightPu].
     */
    fun setTextBoxText(pageId: String, id: ElementId, text: RichText, minHeightPu: Float = 0f, fitHeight: Boolean = false): Boolean = editElements(pageId) { list ->
        list.map { e ->
            if (e.id != id || e !is ObjectElement) return@map e
            val f = e.floating() ?: return@map e
            val tb = f.obj as? TextBoxObject ?: return@map e
            val delta = if (fitHeight) minHeightPu - f.rect.height else (minHeightPu - f.rect.height).coerceAtLeast(0f)
            if (tb.text == text && delta == 0f) e
            else e.withFloating(f.copy(obj = tb.copy(text = text), rect = f.rect.copy(bottom = f.rect.bottom + delta)))
        }
    }

    /** How [finishTextBoxEdit] ended. */
    enum class TextBoxEditResult { Unchanged, Updated, Removed, Cancelled }

    /**
     * Ends the inline editing of text box [id]. An empty box is removed (the reference deletes empty text boxes when they lose
     * focus): a box inserted for this edit ([fresh]) whose insert is still the last undo step is cancelled instead, so
     * nothing is left in the history; otherwise the removal is one step. A non-empty box gets its text (and the grown or,
     * with [fitHeight], fitted height) in one step.
     */
    fun finishTextBoxEdit(
        pageId: String, id: ElementId, text: RichText, heightPu: Float, fresh: Boolean, fitHeight: Boolean = fresh,
    ): TextBoxEditResult {
        if (pages.content(pageId)?.elements?.any { it.id == id } != true) return TextBoxEditResult.Unchanged
        if (text.length == 0) {
            val last = history.peekUndo()
            if (fresh && last is AddElements && last.page == pageId && last.added.singleOrNull()?.element?.id == id) {
                applyingHistory = true
                try {
                    history.undo(this)
                    history.dropRedo()
                } finally {
                    applyingHistory = false
                }
                autosaver.onEdit()
                selection.refresh(pages.content(pageId)?.elements)
                return TextBoxEditResult.Cancelled
            }
            deleteElement(pageId, id)
            return TextBoxEditResult.Removed
        }
        val before = pages.content(pageId)?.elements
        setTextBoxText(pageId, id, text, heightPu, fitHeight)
        return if (pages.content(pageId)?.elements === before) TextBoxEditResult.Unchanged else TextBoxEditResult.Updated
    }

    /** The topmost floating object of [pageId] under [pointPu] (page space), or null. */
    fun objectAt(pageId: String, pointPu: Offset): ObjectElement? =
        pages.content(pageId)?.elements?.asReversed()?.firstOrNull {
            if (it !is ObjectElement) return@firstOrNull false
            // Rotated objects hit-test in their own frame; unreadable ones fall back to their bounds.
            val f = it.floating()
            if (f != null && f.rotationDeg != 0f) f.frame.contains(org.samesame.notes.core.model.document.Pt(pointPu.x, pointPu.y))
            else pointPu.x >= it.bounds.xMin && pointPu.x <= it.bounds.xMax && pointPu.y >= it.bounds.yMin && pointPu.y <= it.bounds.yMax
        } as? ObjectElement

    /** Deletes the element [id] of [pageId] (one command), clearing the selection if it held it. */
    fun deleteElement(pageId: String, id: ElementId) {
        editElements(pageId) { list -> list.filter { it.id != id } }
        selection.refresh(pages.content(pageId)?.elements)
    }

    /** Applies [change] to every selected floating object (one command); ink in the selection is untouched. */
    fun updateSelectedObjects(change: (FloatingObject) -> FloatingObject) {
        val (page, sel) = selectedElements() ?: return
        val ids = sel.mapTo(HashSet()) { it.id }
        editElements(page) { list ->
            list.map { e ->
                if (e.id !in ids || e !is ObjectElement) return@map e
                val f = e.floating() ?: return@map e
                val n = change(f)
                if (n == f) e else e.withFloating(n)
            }
        }
    }

    fun resizeSelectionToDefault() = updateSelectedObjects { if (it.isResizable) it.resizedToDefault() else it }
    fun fitSelectionToPageWidth(pageWidth: Float) = updateSelectedObjects { if (it.obj is ImageObject) it.fittedToWidth(pageWidth) else it }
    fun setSelectionWrap(wrap: TextWrap) = updateSelectedObjects { if (it.supportsWrap) it.withWrap(wrap) else it }

    /** The single selected image or drawing (page id, its object), or null. */
    fun selectedPicture(): Pair<String, FloatingObject>? {
        val (page, sel) = selectedElements() ?: return null
        val f = (sel.singleOrNull() as? ObjectElement)?.floating() ?: return null
        return if (f.obj is ImageObject || f.obj is PaintingObject) page to f else null
    }

    /**
     * Crop / Lasso crop (object menu): [bytes] / [extension] are the cropped pixels of the image (or drawing thumbnail) [id]
     * on [pageId], stored as a new bundle file; the object keeps the kept part in place ([ImageCrop.apply]). One undo step (media files are content-addressed and
     * never deleted, so undo brings the old picture back); the image stays selected.
     */
    suspend fun cropImage(pageId: String, id: ElementId, crop: CropFraction, bytes: ByteArray, extension: String, allowFull: Boolean = false): Boolean {
        val el = pages.content(pageId)?.elements?.firstOrNull { it.id == id } as? ObjectElement ?: return false
        val f = el.floating() ?: return false
        if ((f.obj !is ImageObject && f.obj !is PaintingObject) || (crop.isFull && !allowFull)) return false
        val media = mediaSink(bytes, extension)
        if (media.isEmpty()) return false
        val cropped = el.withFloating(ImageCrop.apply(f, crop, media))
        if (!editElements(pageId) { list -> list.map { if (it.id == id) cropped else it } }) return false
        selection.select(pageId, listOf(cropped))
        return true
    }

    /**
     * Edit image (object menu): an outside editor returned new pixels for the image [id]; they become a new bundle file
     * and the object follows their ratio ([ImageReplace]). One undo step (old media stays in the bundle). Unchanged bytes
     * do nothing.
     */
    suspend fun replaceImage(pageId: String, id: ElementId, bytes: ByteArray, extension: String, pixelW: Int, pixelH: Int): Boolean {
        val el = pages.content(pageId)?.elements?.firstOrNull { it.id == id } as? ObjectElement ?: return false
        val f = el.floating() ?: return false
        if (f.obj !is ImageObject) return false
        val media = mediaSink(bytes, extension)
        if (media.isEmpty()) return false
        if (media == (f.obj as ImageObject).media) return true
        val replaced = el.withFloating(ImageReplace.apply(f, media, pixelW, pixelH))
        if (!editElements(pageId) { list -> list.map { if (it.id == id) replaced else it } }) return false
        selection.select(pageId, listOf(replaced))
        return true
    }

    /** Adds [f] as a new floating object on top of [pageId] (Remove anchor). Returns its element id. */
    fun addFloatingObject(pageId: String, f: FloatingObject): ElementId? {
        val id = randomElementId()
        return if (addElements(pageId, listOf(newObjectElement(id, f)))) id else null
    }

    /** Removes the elements [ids] from [pageId] in one command (Anchor to text moves them into the body). */
    fun removeObjectElements(pageId: String, ids: Set<ElementId>): Boolean = editElements(pageId) { list -> list.filterNot { it.id in ids } }

    /** What Anchor to text can take from the selection: the selected objects (all of an inline-capable type) of its page. */
    fun anchorableSelection(): Pair<String, List<Pair<ElementId, FloatingObject>>>? {
        val (page, sel) = selectedElements() ?: return null
        val picked = sel.mapNotNull { e -> (e as? ObjectElement)?.floating()?.takeIf { AnchorToText.supports(it.obj) }?.let { e.id to it } }
        return if (picked.isEmpty() || picked.size != sel.size) null else page to picked
    }

    /** The selected floating objects (empty when the selection holds ink or is empty). */
    fun selectedObjects(): List<FloatingObject> {
        val (_, sel) = selectedElements() ?: return emptyList()
        return sel.mapNotNull { (it as? ObjectElement)?.floating() }
    }

    /**
     * What the selection's more menu offers, after the reference's rules. [isObjects] false means strokes only: the stroke menu is shown.
     */
    fun objectMenuState(pageWidth: Float): ObjectMenuState {
        val objects = selectedObjects()
        // the reference's single-image test counts every selected item, ink included.
        val single = objects.singleOrNull()?.takeIf { selection.current.ids.size == 1 && it.obj.isImage }
        val fitted = single?.isFittedToWidth(pageWidth) == true
        val onlyObjects = objects.isNotEmpty() && objects.size == selection.current.ids.size
        val only = objects.singleOrNull()?.takeIf { selection.current.ids.size == 1 }?.obj
        return ObjectMenuState(
            isObjects = objects.isNotEmpty(),
            showFitWidth = single != null && !fitted,
            showResize = fitted,
            showWrap = objects.any { it.supportsWrap },
            // the reference ContextMenuDrawing.canShow: edit mode, exactly one selected object, and it is a painting.
            showDrawing = only is PaintingObject,
            behind = objects.firstOrNull()?.wrap == TextWrap.Behind,
            // Crop: one image (GIFs excluded: a crop would freeze the animation) or one drawing (its thumbnail).
            showCrop = (only as? ImageObject)?.media?.endsWith(".gif", ignoreCase = true) == false || only is PaintingObject,
            // Lasso crop: the reference's ContextMenuLassoCrop is Crop without drawings.
            showLassoCrop = (only as? ImageObject)?.media?.endsWith(".gif", ignoreCase = true) == false,
            // Edit image: one image (the reference hands it to its photo editor; we offer any installed editor).
            showEditImage = only is ImageObject,
            // Anchor to text: every selected item is an object of an inline-capable type (image, drawing, voice).
            showAnchor = onlyObjects && objects.all { AnchorToText.supports(it.obj) },
            // Change style: any stroke in the selection (a mixed selection shows the object menu).
            showChangeStyle = objects.isNotEmpty() && !onlyObjects,
            // Share: no strokes; a text box only with text.
            showShare = onlyObjects && objects.all { o -> (o.obj as? TextBoxObject)?.text?.text?.isNotBlank() ?: true },
            // Save image: images and drawings only.
            showSaveImage = onlyObjects && objects.all { it.obj is ImageObject || it.obj is PaintingObject },
            // Play: one voice object.
            showPlay = only is VoiceObject,
        )
    }

    /** Appends [elements] on top of the page and records one command. */
    fun addElements(pageId: String, elements: List<PageElement>): Boolean {
        if (elements.isEmpty()) return true
        val start = pages.content(pageId)?.elements?.size ?: return false
        pages.edit(pageId) { it + elements }
        history.record(AddElements(pageId, elements.mapIndexed { i, e -> IndexedElement(start + i, e) }))
        return true
    }

    /** Applies [transform] to a loaded page and records the resulting delta (erase results, bakes, restyles). */
    fun editElements(pageId: String, transform: (List<PageElement>) -> List<PageElement>): Boolean {
        val old = pages.content(pageId)?.elements ?: return false
        val new = transform(old)
        val delta = ReplaceElements.diff(pageId, old, new) ?: return true
        pages.edit(pageId) { new }
        history.record(delta)
        selection.refresh(new)
        return true
    }

    /** Live preview during a drag (eraser, transform): changes the page without recording history. */
    fun previewElements(pageId: String, elements: List<PageElement>) {
        pages.edit(pageId) { elements }
    }

    /** Records the net change of a drag whose intermediate states were applied with [previewElements]. */
    fun recordApplied(pageId: String, original: List<PageElement>, current: List<PageElement>) {
        ReplaceElements.diff(pageId, original, current)?.let { history.record(it) }
        selection.refresh(current)
    }

    // selection operations (the selection pill: Cut, Copy, Paste, Delete, more)

    fun select(pageId: String, elements: List<PageElement>) = selection.select(pageId, elements)
    fun clearSelection() = selection.clear()

    private fun selectedElements(): Pair<String, List<PageElement>>? {
        val s = selection.current
        val page = s.pageId ?: return null
        val all = pages.content(page)?.elements ?: return null
        return page to all.filter { it.id in s.ids }
    }

    fun deleteSelection() {
        val (page, sel) = selectedElements() ?: return
        if (sel.isEmpty()) return
        val ids = sel.mapTo(HashSet()) { it.id }
        editElements(page) { list -> list.filter { it.id !in ids } }
        selection.clear()
    }

    fun copySelection() {
        val (page, sel) = selectedElements() ?: return
        val b = selection.current.bounds ?: return
        if (sel.isNotEmpty()) clipboard.set(ClipboardContent(sel, page, Offset(b.left, b.top), b.width, b.height))
    }

    fun cutSelection() {
        copySelection()
        deleteSelection()
    }

    /**
     * Pastes onto [targetPageId]. [originVisible]: the copied position is on screen; [viewportCenterInPage] is the
     * viewport centre in the target page's space. The pasted items become the selection. One undo step.
     */
    fun paste(targetPageId: String, originVisible: Boolean, viewportCenterInPage: Offset): Boolean {
        val c = clipboard.content ?: return false
        val offset = clipboard.nextPasteOffset(originVisible, viewportCenterInPage) ?: return false
        val m = StrokeTransforms.translate(offset.x, offset.y)
        val ids = c.elements.mapTo(HashSet()) { it.id }
        // Fresh ids so repeated pastes never collide.
        val moved = StrokeTransforms.apply(c.elements, ids, m).map { e ->
            val fresh = randomElementId()
            when (e) {
                is InkElement -> InkElement(fresh, e.stroke)
                is org.samesame.notes.core.ink.page.ObjectElement -> org.samesame.notes.core.ink.page.ObjectElement(fresh, e.json, e.bounds)
            }
        }
        if (!addElements(targetPageId, moved)) return false
        selection.select(targetPageId, moved)
        return true
    }

    /** Bakes [m] (page space) into the selected elements as one command. */
    fun transformSelection(m: AffineTransform) {
        val (page, sel) = selectedElements() ?: return
        val ids = sel.mapTo(HashSet()) { it.id }
        editElements(page) { StrokeTransforms.apply(it, ids, m) }
        selection.setLiveTransform(null)
    }

    /** Change style (`#52`): colour and/or size and/or pen family of the selected strokes. One command. */
    fun restyleSelection(colorArgb: Int? = null, sizePu: Float? = null, familyKey: String? = null) {
        val (page, sel) = selectedElements() ?: return
        val ids = sel.mapTo(HashSet()) { it.id }
        editElements(page) { StrokeTransforms.restyle(it, ids, colorArgb, sizePu, familyKey) }
    }

    /**
     * Straighten (stroke menu): every selected stroke that is close to a straight line becomes the exact segment
     * ([Straighten]). One undo step. Returns how many strokes changed (0 = nothing line-like was selected).
     */
    fun straightenSelection(): Int {
        val (page, sel) = selectedElements() ?: return 0
        val ids = sel.mapTo(HashSet()) { it.id }
        var changed = 0
        editElements(page) { list ->
            list.map { e ->
                if (e !is InkElement || e.id !in ids) return@map e
                val inputs = e.stroke.inputs
                val pts = ArrayList<org.samesame.notes.core.model.document.Pt>(inputs.size)
                val q = androidx.ink.strokes.StrokeInput()
                for (i in 0 until inputs.size) { inputs.populate(i, q); pts += org.samesame.notes.core.model.document.Pt(q.x, q.y) }
                val seg = Straighten.segmentFor(pts) ?: return@map e
                changed++
                InkElement(e.id, org.samesame.notes.core.ink.tools.StraightLine.toSegment(e.stroke, seg.a.x, seg.a.y, seg.b.x, seg.b.y))
            }
        }
        return changed
    }

    /** Bottom layer: the selected objects go below the text and to the start of the page's element list. One undo step. */
    fun sendSelectionToBack() = layerSelection(toBack = true)

    /** Top layer: the selected objects go in front of the text and to the end of the page's element list. One undo step. */
    fun bringSelectionToFront() = layerSelection(toBack = false)

    private fun layerSelection(toBack: Boolean) {
        val s = selection.current
        val page = s.pageId ?: return
        val old = pages.content(page)?.elements ?: return
        val ids = s.ids
        val wrap = if (toBack) TextWrap.Behind else TextWrap.InFront
        val rewrapped = old.map { e ->
            val f = (e as? ObjectElement)?.takeIf { it.id in ids }?.floating()
            if (f != null && f.supportsWrap && f.wrap != wrap) e.withFloating(f.withWrap(wrap)) else e
        }
        val new = ZOrder.toEdge(rewrapped, ids, toBack)
        val wrapDelta = ReplaceElements.diff(page, old, rewrapped)
        val orderChanged = new.map { it.id } != rewrapped.map { it.id }
        if (wrapDelta == null && !orderChanged) return
        val commands = listOfNotNull(wrapDelta, if (orderChanged) Reorder(page, rewrapped.map { it.id }, new.map { it.id }) else null)
        pages.edit(page) { new }
        history.record(if (commands.size == 1) commands[0] else Group(commands))
        selection.refresh(new)
    }

    fun moveSelectionForward() = reorderSelection(ZOrder::forward)
    fun moveSelectionBackward() = reorderSelection(ZOrder::backward)

    private fun reorderSelection(f: (List<PageElement>, Set<ElementId>, androidx.compose.ui.geometry.Rect) -> List<PageElement>) {
        val s = selection.current
        val page = s.pageId ?: return
        val bounds = s.bounds ?: return
        val old = pages.content(page)?.elements ?: return
        val new = f(old, s.ids, bounds)
        if (new.map { it.id } == old.map { it.id }) return
        val before = old.map { it.id }
        pages.edit(page) { new }
        history.record(Reorder(page, before, new.map { it.id }))
    }

    // ------------------------------------------------------------------------------------------ pages

    /** Inserts [spec] at [index] (a new empty layer). */
    fun addPage(spec: PageSpec, index: Int = holder.current.pages.size) {
        val before = holder.current.pages
        val after = before.toMutableList().also { it.add(index.coerceIn(0, it.size), spec) }
        applyPageList(before, after, emptyList())
    }

    /**
     * Inserts [specs] as new pages right after the page [afterPageId] (the end when it is no longer in the note). One undo
     * step. Returns false when there is nothing to insert.
     */
    fun insertPagesAfter(afterPageId: String, specs: List<PageSpec>): Boolean {
        if (specs.isEmpty()) return false
        val before = holder.current.pages
        val at = before.indexOfFirst { it.id == afterPageId }.let { if (it < 0) before.size else it + 1 }
        val after = before.toMutableList().also { it.addAll(at, specs) }
        applyPageList(before, after, emptyList())
        return true
    }

    /** Removes pages (their layers are kept in the command for undo). Suspends to load layers not resident. */
    suspend fun deletePages(ids: Set<String>) {
        val before = holder.current.pages
        if (before.none { it.id in ids } || before.all { it.id in ids }) return // a note keeps at least one page
        val detached = ids.mapNotNull { pages.await(it) }
        applyPageList(before, before.filter { it.id !in ids }, detached)
    }

    /** Reorders pages (page sorter). */
    fun reorderPages(newOrder: List<PageSpec>) = applyPageList(holder.current.pages, newOrder, emptyList())

    fun updatePageSpec(id: String, transform: (PageSpec) -> PageSpec) {
        val before = holder.current.pages.firstOrNull { it.id == id } ?: return
        val after = transform(before)
        if (after == before) return
        holder.updatePage(after)
        history.record(PageSpecChange(id, before, after))
        autosaver.onEdit()
    }

    private fun applyPageList(before: List<PageSpec>, after: List<PageSpec>, detached: List<PageContent>) {
        history.record(PageListChange(before, after, detached))
        setPageSpecs(after)
        detached.forEach { detachPage(it.pageId) }
    }

    /**
     * Copies the pages [ids] (in note order) with their layers and the media they use (page sorter Copy). Null when
     * none of them is a page of this note or a layer cannot be loaded. Body text is not copied: it flows across pages.
     */
    suspend fun copyPages(ids: Set<String>): PageClip? {
        val specs = holder.current.pages.filter { it.id in ids }
        if (specs.isEmpty()) return null
        val entries = ArrayList<PageClipEntry>(specs.size)
        for (spec in specs) {
            val content = pages.await(spec.id) ?: return null
            entries += PageClipEntry(spec, content.elements)
        }
        val paths = LinkedHashSet<String>()
        for (spec in specs) {
            (spec.template as? TemplateRef.Image)?.let { paths += it.media }
            spec.pdf?.let { paths += it.media }
        }
        for (e in entries) for (el in e.elements) {
            (el as? ObjectElement)?.floating()?.obj?.mediaPaths()?.let { paths += it }
        }
        val media = HashMap<String, ByteArray>()
        for (p in paths) mediaLoader(p)?.let { media[p] = it }
        return PageClip(entries, media)
    }

    /**
     * Pastes [clip] as new pages after page index [afterIndex] (page sorter Paste; -1 = before the first page). The media
     * is stored again in this note's bundle and the copies point at it, so pages from another note keep their pictures.
     * One undo step. Returns the new page ids; empty when there is nothing to paste or a media file cannot be stored.
     */
    suspend fun pastePages(afterIndex: Int, clip: PageClip): List<String> {
        if (clip.pages.isEmpty()) return emptyList()
        val remap = HashMap<String, String>()
        for ((path, bytes) in clip.media) {
            val stored = mediaSink(bytes, path.substringAfterLast('.', "bin"))
            if (stored.isEmpty()) return emptyList()
            remap[path] = stored
        }
        val newSpecs = clip.pages.map { e ->
            val spec = e.spec
            spec.copy(
                id = java.util.UUID.randomUUID().toString(),
                template = (spec.template as? TemplateRef.Image)?.let { TemplateRef.Image(remap[it.media] ?: it.media) } ?: spec.template,
                pdf = spec.pdf?.let { it.copy(media = remap[it.media] ?: it.media) },
            )
        }
        val added = clip.pages.mapIndexed { i, e ->
            PageContent(newSpecs[i].id, e.elements.map { remapElement(it, remap) }, version = 0)
        }
        val before = holder.current.pages
        val at = (afterIndex + 1).coerceIn(0, before.size)
        val after = before.toMutableList().also { it.addAll(at, newSpecs) }
        added.forEach { pages.attach(it) }
        setPageSpecs(after)
        history.record(PagePasteChange(before, after, added))
        return newSpecs.map { it.id }
    }

    /** Fresh element ids (repeated pastes never collide); image and voice objects point at the remapped media. */
    private fun remapElement(e: PageElement, remap: Map<String, String>): PageElement {
        val fresh = randomElementId()
        return when (e) {
            is InkElement -> InkElement(fresh, e.stroke)
            is ObjectElement -> {
                val f = e.floating() ?: return ObjectElement(fresh, e.json, e.bounds)
                val obj = when (val o = f.obj) {
                    is ImageObject -> o.copy(media = remap[o.media] ?: o.media)
                    is VoiceObject -> o.copy(media = remap[o.media] ?: o.media)
                    is PaintingObject -> o.copy(media = remap[o.media] ?: o.media, ink = remap[o.ink] ?: o.ink)
                    else -> o
                }
                val json = if (obj === f.obj) e.json else f.copy(obj = obj).toJson()
                ObjectElement(fresh, json, e.bounds)
            }
        }
    }

    private fun NoteObject.mediaPath(): String? = when (this) {
        is ImageObject -> media
        is VoiceObject -> media
        is PaintingObject -> media
        else -> null
    }

    /** Every bundle file an object points at (a drawing has two: thumbnail and strokes). */
    private fun NoteObject.mediaPaths(): List<String> = when (this) {
        is PaintingObject -> listOf(media, ink)
        else -> listOfNotNull(mediaPath())
    }

    // ------------------------------------------------------------------------------------------ undo / redo

    /** Undoes one step. Suspends only to load pages the step touches. Serialised with [redo]. */
    suspend fun undo(): Boolean = historyMutex.withLock {
        val cmd = history.peekUndo() ?: return@withLock false
        if (!ensureLoaded(cmd.pageIds)) return@withLock false
        runHistory { history.undo(this) }
    }

    suspend fun redo(): Boolean = historyMutex.withLock {
        val cmd = history.peekRedo() ?: return@withLock false
        if (!ensureLoaded(cmd.pageIds)) return@withLock false
        runHistory { history.redo(this) }
    }

    private suspend fun ensureLoaded(ids: Set<String>): Boolean = ids.all { pages.await(it) != null }

    private inline fun runHistory(block: () -> Boolean): Boolean {
        applyingHistory = true
        try {
            val done = block()
            if (done) {
                holder.onBodyChanged() // conservative: a text command may have changed the body
                autosaver.onEdit()
                selection.refresh(selection.current.pageId?.let { pages.content(it)?.elements })
            }
            return done
        } finally {
            applyingHistory = false
        }
    }

    // ------------------------------------------------------------------------------------------ EditTarget

    override fun insertElements(pageId: String, items: List<IndexedElement>) {
        pages.edit(pageId) { list ->
            list.toMutableList().also { m -> items.forEach { m.add(it.index.coerceAtMost(m.size), it.element) } }
        }
    }

    override fun removeElements(pageId: String, ids: Set<ElementId>) {
        pages.edit(pageId) { list -> list.filter { it.id !in ids } }
    }

    override fun setElementOrder(pageId: String, order: List<ElementId>) {
        pages.edit(pageId) { list ->
            val byId = list.associateBy { it.id }
            order.mapNotNull { byId[it] }
        }
    }

    override fun setPageSpecs(pages: List<PageSpec>) {
        val known = holder.current.pages.mapTo(HashSet()) { it.id }
        holder.setPages(pages)
        for (p in pages) if (p.id !in known && this.pages.slot(p.id) == null) this.pages.create(p.id)
        if (selection.current.pageId?.let { id -> pages.none { it.id == id } } == true) selection.clear()
        autosaver.onEdit()
    }

    override fun setInlineObject(id: String, obj: NoteObject?) {
        holder.setInlineObject(id, obj)
        autosaver.onEdit()
    }

    override fun setRecordings(recordings: List<NoteRecording>) {
        holder.setRecordings(recordings)
        autosaver.onEdit()
    }

    override fun replacePageSpec(spec: PageSpec) {
        holder.updatePage(spec)
        autosaver.onEdit()
    }

    override fun detachPage(pageId: String) {
        pages.detach(pageId)
    }

    override fun attachPage(content: PageContent) {
        pages.attach(content)
    }

    // ------------------------------------------------------------------------------------------ lifecycle

    /** "Auto save notes" setting. When off nothing is written until [saveNow]; [discardChanges] drops what is unsaved. */
    var autoSave: Boolean
        get() = autosaver.enabled
        set(value) { autosaver.enabled = value }

    /** Unsaved edits exist (only meaningful with [autoSave] off; with it on they are saved within seconds). */
    fun hasUnsavedChanges(): Boolean = holder.isDirty || pages.hasDirty()

    /** The Save choice of the leave dialog: write and pack now even though auto save is off. */
    suspend fun saveNow() = autosaver.flush(close = false, force = true)

    /** The Discard choice: stop saving for good, so [finish] only closes the working copy. */
    fun discardChanges() {
        autosaver.enabled = false
    }

    /** `ON_STOP`: save and pack now. */
    suspend fun flush() = autosaver.flush(close = false)

    /** The editor closed: stop the loops, save, pack and drop the working copy. Run on an app-lifetime scope. */
    suspend fun finish() {
        autosaver.stop()
        autosaver.flush(close = true)
    }
}

/** Items of the selection's more menu (see [EditorSession.objectMenuState]). */
data class ObjectMenuState(
    val isObjects: Boolean,
    val showFitWidth: Boolean,
    val showResize: Boolean,
    val showWrap: Boolean,
    val behind: Boolean,
    /** Exactly one painting is selected: the more menu offers "Drawing" (re-edit). */
    val showDrawing: Boolean = false,
    val showCrop: Boolean = false,
    val showChangeStyle: Boolean = false,
    val showShare: Boolean = false,
    val showSaveImage: Boolean = false,
    val showPlay: Boolean = false,
    val showLassoCrop: Boolean = false,
    val showEditImage: Boolean = false,
    val showAnchor: Boolean = false,
)

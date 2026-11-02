package org.samesame.notes.feature.composer.drawing

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.samesame.notes.core.designsystem.component.AppDialog
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.document.PaintingObject
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.richtext.BodyText
import org.samesame.notes.core.richtext.spans.TextRenderEnv
import org.samesame.notes.core.richtext.ui.rememberRichTextEditorState
import org.samesame.notes.feature.composer.ComposerPanelLayer
import org.samesame.notes.feature.composer.R
import org.samesame.notes.feature.composer.ToolOverlayLayer
import org.samesame.notes.feature.composer.canvas.DocumentLayout
import org.samesame.notes.feature.composer.canvas.NoteCanvas
import org.samesame.notes.feature.composer.canvas.Viewport
import org.samesame.notes.feature.composer.input.InputMode
import org.samesame.notes.feature.composer.input.InputSettings
import org.samesame.notes.feature.composer.input.InputTool
import org.samesame.notes.feature.composer.session.EditorSession
import org.samesame.notes.feature.composer.session.SessionPageSource
import org.samesame.notes.feature.composer.session.tools.PageHit
import org.samesame.notes.feature.composer.session.tools.PageLocator
import org.samesame.notes.feature.composer.session.tools.SessionToolHandlers
import org.samesame.notes.feature.composer.toColor
import org.samesame.notes.feature.composer.toolbar.EditorMode
import org.samesame.notes.feature.composer.toolbar.Panel
import org.samesame.notes.feature.composer.toolbar.PenModeTool
import org.samesame.notes.feature.composer.toolbar.PenModeToolbar
import org.samesame.notes.feature.composer.toolbar.PenStyles
import org.samesame.notes.feature.composer.toolbar.PenToolbarModel
import org.samesame.notes.feature.composer.toolbar.ToolbarHeight
import org.samesame.notes.feature.composer.toolbar.ToolbarIntent

/** What the composer asks the sheet to do: a new drawing, or re-edit [painting] (the object [elementId]). */
internal class DrawingRequest(
    val pageId: String,
    /** Null = a new drawing. */
    val elementId: org.samesame.notes.core.ink.page.ElementId?,
    val painting: PaintingObject?,
    /** Where a new drawing is centred on [pageId] (page space). */
    val centerPu: Offset,
)

/** A finished drawing, ready to store. */
internal class DrawingResult(val thumbnail: ByteArray, val ink: ByteArray, val content: RectPu)

/**
 * The full-screen drawing sheet (Insert > Drawing): a separate canvas, the screen width wide and 1.5x that tall, with
 * the pen toolbar and panels of the editor. Done saves and closes; the back arrow and system back discard (asking first
 * when there is something to lose), and an untouched or empty sheet leaves silently. Built on an in-memory
 * [EditorSession] made by [newSession].
 */
@Composable
internal fun DrawingSheet(
    request: DrawingRequest,
    newSession: (CoroutineScope, List<PageElement>) -> EditorSession,
    loadInk: suspend (String) -> ByteArray?,
    settings: AppSettings,
    onDone: (DrawingResult?) -> Unit,
) {
    // The strokes of a re-edit load first (decoding is off the main thread).
    var initial by remember(request) { mutableStateOf<List<PageElement>?>(if (request.painting == null) emptyList() else null) }
    LaunchedEffect(request) {
        val painting = request.painting ?: return@LaunchedEffect
        initial = withContext(Dispatchers.IO) {
            runCatching { loadInk(painting.ink)?.let(DrawingFiles::decodeInk) }.getOrNull() ?: emptyList()
        }
    }
    val elements = initial
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (elements == null) {
            BackHandler { onDone(null) }
        } else {
            SheetEditor(elements, newSession, settings, onDone)
        }
    }
}

@Composable
private fun SheetEditor(
    elements: List<PageElement>,
    newSession: (CoroutineScope, List<PageElement>) -> EditorSession,
    settings: AppSettings,
    onDone: (DrawingResult?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val session = remember { newSession(scope, elements) }
    val chrome by session.chrome.collectAsStateWithLifecycle()
    val tools by session.tools.collectAsStateWithLifecycle()
    val doc by session.document.collectAsStateWithLifecycle()
    val selectionState = session.selectionState.collectAsStateWithLifecycle()
    val tb = chrome.toolbar
    val prefs = tools.prefs
    val dark = SameSameTheme.colors.isDark

    // The sheet is a pen surface only: no text mode, no reading mode.
    LaunchedEffect(session) {
        if (session.chrome.value.toolbar.mode != EditorMode.Pen) session.dispatch(ToolbarIntent.ToggleHandwriting)
        if (session.chrome.value.toolbar.tool == PenModeTool.Selection) session.dispatch(ToolbarIntent.TapTool(PenModeTool.Pen))
    }

    val layout = remember(doc.pages, doc.layout) { DocumentLayout(doc.pages, doc.layout) }
    val viewport = remember(session) { Viewport() }
    val source = remember(session) { SessionPageSource(session) }
    val handlers = remember(session) {
        SessionToolHandlers(
            session,
            locator = PageLocator { p ->
                val l = DocumentLayout(session.holder.current.pages.take(1), session.holder.current.layout)
                if (l.pageAt(p) == null) null else PageHit(DrawingGeometry.SHEET_PAGE_ID, l.pageOrigin(0))
            },
            handleRadiusPu = { 24f * density.density / viewport.scale },
        )
    }
    // The panels only need a body for their text sections, which the sheet never opens.
    val body = remember(session) { BodyText(session.initialBody, TextRenderEnv()) }
    val textState = rememberRichTextEditorState(body, layout)

    val inputSettings = InputSettings(
        mode = InputMode.Pen,
        tool = when (tb.tool) {
            PenModeTool.Pen -> InputTool.Pen
            PenModeTool.Highlighter -> InputTool.Highlighter
            PenModeTool.Eraser -> InputTool.Eraser
            PenModeTool.Selection -> InputTool.Selection
        },
        fingerDrawing = prefs.fingerDrawing,
        zoomLock = prefs.zoomLock && settings.toolbarZoomLock,
    )
    val penStyle = remember(tb.tool, prefs.penType, prefs.penLevel, prefs.penColor, prefs.penPressureSensitive, prefs.highlighterLevel, prefs.highlighterColor, prefs.highlighterOpacity, prefs.highlighterTip) {
        PenStyles.current(tb.tool, prefs)
    }
    val brush = remember(penStyle) { penStyle.brush() }

    var eraseAllOpen by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(tb.panel) { if (tb.panel != Panel.Eraser) eraseAllOpen = false }
    fun eraseAll() {
        eraseAllOpen = false
        session.dispatch(ToolbarIntent.ClosePanel)
        session.editElements(DrawingGeometry.SHEET_PAGE_ID) { emptyList() }
    }
    fun undo() { scope.launch { session.undo() } }
    fun redo() { scope.launch { session.redo() } }

    var closing by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val nothingToSave = stringResource(R.string.feature_composer_drawing_nothing_to_save)
    suspend fun currentStrokes(): List<InkElement> =
        session.pages.await(DrawingGeometry.SHEET_PAGE_ID)?.elements.orEmpty().filterIsInstance<InkElement>()

    /** Done: decides by [DrawingGeometry.outcome] and stores the drawing through [onDone]. */
    fun saveAndClose() {
        if (closing) return
        closing = true
        scope.launch {
            val strokes = currentStrokes()
            when (DrawingGeometry.outcome(session.chrome.value.undo.canUndo, strokes.size)) {
                DrawingGeometry.Outcome.Unchanged -> onDone(null)
                DrawingGeometry.Outcome.NothingToSave -> {
                    Toast.makeText(context, nothingToSave, Toast.LENGTH_SHORT).show()
                    onDone(null)
                }
                DrawingGeometry.Outcome.Save -> {
                    val result = withContext(Dispatchers.Default) {
                        val content = DrawingGeometry.contentRect(strokes.map { RectPu(it.bounds.xMin, it.bounds.yMin, it.bounds.xMax, it.bounds.yMax) })
                        content?.let { DrawingResult(DrawingFiles.renderThumbnail(strokes.map { s -> s.stroke }, it), DrawingFiles.encodeInk(strokes), it) }
                    }
                    if (result == null) {
                        Toast.makeText(context, nothingToSave, Toast.LENGTH_SHORT).show()
                        onDone(null)
                    } else onDone(result)
                }
            }
        }
    }

    /** Leaves without saving; the caller keeps the existing painting when [onDone] gets null. */
    fun discardNow() {
        if (closing) return
        closing = true
        onDone(null)
    }

    /** Back arrow / system back: decides by [DrawingGeometry.discardOutcome]. */
    fun requestDiscard() {
        if (closing) return
        scope.launch {
            when (DrawingGeometry.discardOutcome(session.chrome.value.undo.canUndo, currentStrokes().size)) {
                DrawingGeometry.DiscardOutcome.Silent -> discardNow()
                DrawingGeometry.DiscardOutcome.Confirm -> confirmDiscard = true
            }
        }
    }
    // Back: a panel closes first, then a selection, then the sheet (discarding).
    BackHandler(enabled = true) {
        when {
            tb.panel != null -> session.dispatch(ToolbarIntent.ClosePanel)
            chrome.hasSelection -> session.clearSelection()
            else -> requestDiscard()
        }
    }

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clickable(role = Role.Button, onClick = ::requestDiscard),
                    contentAlignment = Alignment.Center,
                ) { Icon(AppIcons.Back, stringResource(R.string.feature_composer_back), Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onBackground) }
                Text(
                    stringResource(R.string.feature_composer_drawing_title),
                    color = MaterialTheme.colorScheme.onBackground, fontSize = 22.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.feature_composer_done),
                    color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp,
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = ::saveAndClose)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                NoteCanvas(
                    source = source,
                    viewport = viewport,
                    brush = brush,
                    inputSettings = inputSettings,
                    modifier = Modifier.fillMaxSize(),
                    darkTheme = dark,
                    toolHandler = handlers::forTool,
                    onStylusSeen = { if (!prefs.stylusSeen) session.toolController.updatePrefs { it.withStylusSeen() } },
                    straightLine = penStyle.straightLine,
                    overlay = { ToolOverlayLayer(handlers, selectionState, listOf(DrawingGeometry.SHEET_PAGE_ID), layout, viewport, dark) },
                )
            }
            PenModeToolbar(
                model = PenToolbarModel(
                    tool = tb.tool,
                    penColor = prefs.penColor.toColor(),
                    highlighterColor = prefs.highlighterColor.toColor(),
                    highlighterOpacity = prefs.highlighterOpacity,
                    selectionShape = tools.selectionShape,
                    canUndo = chrome.undo.canUndo,
                    canRedo = chrome.undo.canRedo,
                ),
                onKeyboard = {},
                onTool = { session.dispatch(ToolbarIntent.TapTool(it)) },
                onUndo = ::undo,
                onRedo = ::redo,
                onStyle = {},
                onEasyPad = {},
                showStyle = false,
                showEasyPad = false,
                showKeyboard = false,
            )
        }
        ComposerPanelLayer(
            session = session,
            body = body,
            activeState = textState,
            tb = tb,
            tools = tools,
            eraseAllOpen = eraseAllOpen,
            onEraseAll = { eraseAllOpen = !eraseAllOpen },
            onEraseAllPage = { eraseAll() },
            currentPage = 0,
            pageCount = 1,
            modifier = Modifier.padding(bottom = ToolbarHeight),
        )
    }
    if (confirmDiscard) {
        AppDialog(
            onDismissRequest = { confirmDiscard = false },
            dismissLabel = stringResource(R.string.feature_composer_cancel),
            confirmLabel = stringResource(R.string.feature_composer_leave_discard),
            onConfirm = { confirmDiscard = false; discardNow() },
            atBottom = true,
        ) {
            Text(
                stringResource(R.string.feature_composer_drawing_discard_confirm),
                style = SameSameText.DialogMessage.capped(), color = SameSameTheme.colors.textPrimary,
            )
        }
    }
}

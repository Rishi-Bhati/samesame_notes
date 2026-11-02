package org.samesame.notes.feature.composer

import org.samesame.notes.core.designsystem.icon.AppIcons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.ink.DarkModeInk
import org.samesame.notes.core.ink.brush.PenCatalog
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.document.ViewState
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.richtext.BodyText
import org.samesame.notes.core.richtext.RichTextColors
import org.samesame.notes.core.richtext.spans.InlineObjectSpan
import org.samesame.notes.core.richtext.spans.TextRenderEnv
import org.samesame.notes.core.richtext.input.richTextInput
import org.samesame.notes.core.richtext.layout.ViewTransform
import org.samesame.notes.core.richtext.ui.RichTextCaretBlink
import org.samesame.notes.core.richtext.ui.RichTextToolbarEffect
import org.samesame.notes.core.richtext.ui.rememberRichTextEditorState
import org.samesame.notes.core.richtext.ui.richTextGestures
import org.samesame.notes.core.richtext.ui.richTextSelectionDecorations
import org.samesame.notes.feature.composer.canvas.DocumentLayout
import org.samesame.notes.feature.composer.canvas.NoteCanvas
import org.samesame.notes.feature.composer.canvas.Viewport
import org.samesame.notes.feature.composer.easypad.EasyWritingPad
import org.samesame.notes.feature.composer.easypad.EasyPadMath
import org.samesame.notes.feature.composer.search.NoteSearchBar
import org.samesame.notes.feature.composer.search.SearchNavigator
import org.samesame.notes.feature.composer.easypad.PadSlot
import org.samesame.notes.feature.composer.input.InputMode
import org.samesame.notes.feature.composer.input.InputSettings
import org.samesame.notes.feature.composer.input.InputTool
import org.samesame.notes.feature.composer.panels.FontSizeHoldState
import org.samesame.notes.feature.composer.panels.FontSizePick
import org.samesame.notes.feature.composer.panels.TextSizeCatalog
import org.samesame.notes.feature.composer.panels.fontSizeButtonLabel
import org.samesame.notes.feature.composer.session.EditorSession
import org.samesame.notes.feature.composer.session.SessionPageSource
import org.samesame.notes.feature.composer.session.textBoxInsertTopLeft
import org.samesame.notes.feature.composer.session.tools.PageHit
import org.samesame.notes.feature.composer.session.tools.PageLocator
import org.samesame.notes.feature.composer.session.tools.SessionToolHandlers
import org.samesame.notes.feature.composer.text.BodyTextBinding
import org.samesame.notes.feature.composer.toolbar.BackAction
import org.samesame.notes.feature.composer.toolbar.BackOutcome
import org.samesame.notes.feature.composer.toolbar.BackPolicy
import org.samesame.notes.core.designsystem.component.AppDialog
import org.samesame.notes.core.designsystem.component.ScrollBarGeometry
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.richtext.TextLink
import org.samesame.notes.feature.composer.toolbar.EditorMode
import org.samesame.notes.feature.composer.toolbar.Panel
import org.samesame.notes.feature.composer.toolbar.PenModeTool
import org.samesame.notes.feature.composer.toolbar.PenModeToolbar
import org.samesame.notes.feature.composer.toolbar.PenStyles
import org.samesame.notes.feature.composer.toolbar.PenToolbarModel
import org.samesame.notes.feature.composer.toolbar.TextToolbar
import org.samesame.notes.feature.composer.toolbar.TextToolbarButton
import org.samesame.notes.feature.composer.toolbar.TextToolbarModel
import org.samesame.notes.feature.composer.toolbar.ToolbarIntent
import org.samesame.notes.feature.composer.toolbar.ToolbarReducer
import org.samesame.notes.feature.composer.toolbar.ToolbarState
import org.samesame.notes.feature.composer.toolbar.ToolbarHeight
import org.samesame.notes.feature.composer.drawing.DrawingRequest
import org.samesame.notes.feature.composer.drawing.DrawingResult
import org.samesame.notes.feature.composer.drawing.DrawingSheet
import org.samesame.notes.core.model.document.PaintingObject
import org.samesame.notes.core.model.document.SizePu
import java.util.UUID

import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.activity.result.PickVisualMediaRequest
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.TemplateRef
import kotlin.math.roundToInt
import org.samesame.notes.core.export.ExportFormat
import org.samesame.notes.core.export.ExportOptions
import org.samesame.notes.core.export.ExportTarget
import org.samesame.notes.core.export.expectedTarget
import org.samesame.notes.core.model.document.ScrollDirection
import org.samesame.notes.core.model.document.TextWrap
import org.samesame.notes.core.model.document.TextBoxObject
import org.samesame.notes.core.model.document.VoiceObject
import org.samesame.notes.core.model.document.rotatedBounds
import org.samesame.notes.core.model.document.frame
import org.samesame.notes.feature.composer.voice.MicPermission
import org.samesame.notes.feature.composer.voice.RecorderState
import org.samesame.notes.feature.composer.voice.RecordingAnchor
import org.samesame.notes.feature.composer.voice.StartOutcome
import org.samesame.notes.feature.composer.voice.VoiceNotice
import org.samesame.notes.feature.composer.voice.Track
import org.samesame.notes.feature.composer.voice.VoiceDockedMenu
import org.samesame.notes.feature.composer.voice.VoiceRecordPill
import org.samesame.notes.feature.composer.voice.VoiceListState
import org.samesame.notes.feature.composer.voice.VoicePlaybackHub
import org.samesame.notes.feature.composer.voice.isChangingConfigurations
import org.samesame.notes.feature.composer.voice.onEditorDisposed
import org.samesame.notes.feature.composer.voice.onEditorStopped
import org.samesame.notes.feature.composer.voice.VoiceRecordService
import org.samesame.notes.feature.composer.voice.VoiceRecording
import org.samesame.notes.feature.composer.voice.VoiceRecordingHub
import org.samesame.notes.feature.composer.voice.VoiceRecordingList
import org.samesame.notes.feature.composer.voice.VoiceRecordingListPanel
import org.samesame.notes.feature.composer.voice.rememberMicPermission
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.floating
import org.samesame.notes.feature.composer.text.TextBoxEditOverlay
import org.samesame.notes.feature.composer.text.TextBoxEditor
import org.samesame.notes.feature.composer.text.TextBoxTapAction
import org.samesame.notes.feature.composer.text.textBoxTapAction
import org.samesame.notes.feature.composer.text.textBoxTaps
import org.samesame.notes.feature.composer.text.plainTaps
import org.samesame.notes.feature.composer.menus.ContextMenuPlacement
import org.samesame.notes.feature.composer.menus.ObjectMenuExtras
import org.samesame.notes.feature.composer.menus.ObjectFileActions
import org.samesame.notes.feature.composer.canvas.ImageCropOverlay
import org.samesame.notes.feature.composer.canvas.EditorScrollBar
import org.samesame.notes.feature.composer.canvas.PullToAddPageIndicator
import org.samesame.notes.feature.composer.canvas.PullToAddPageState
import org.samesame.notes.feature.composer.session.CropFraction
import org.samesame.notes.feature.composer.session.LassoCrop
import org.samesame.notes.feature.composer.session.AnchorToText
import org.samesame.notes.feature.composer.text.ExtendedTextToolbar
import org.samesame.notes.feature.composer.text.TextActions
import org.samesame.notes.feature.composer.canvas.LassoCropOverlay
import org.samesame.notes.feature.composer.canvas.LiveSelection
import org.samesame.notes.feature.composer.canvas.ObjectImageCache
import org.samesame.notes.feature.composer.canvas.adjustsForDark
import org.samesame.notes.feature.composer.canvas.InlineObjectPainter
import org.samesame.notes.feature.composer.canvas.ObjectPainters
import org.samesame.notes.feature.share.PageSaveFormats
import org.samesame.notes.feature.share.PageShareFormats
import org.samesame.notes.core.data.pdf.PdfImportException
import org.samesame.notes.feature.share.SaveAsFileDialog
import org.samesame.notes.feature.share.ShareFiles
import org.samesame.notes.feature.share.ShareFormatDialog
import org.samesame.notes.feature.composer.dialogs.TagsDialog
import org.samesame.notes.feature.composer.menus.InsertMenu
import org.samesame.notes.feature.composer.menus.OverflowAction
import org.samesame.notes.feature.composer.menus.OverflowMenu
import org.samesame.notes.feature.composer.menus.ReadingAction
import org.samesame.notes.feature.composer.menus.ReadingOverflowMenu
import org.samesame.notes.feature.composer.menus.PageSettingsPopup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import org.samesame.notes.core.designsystem.motion.MotionPopup
import org.samesame.notes.core.designsystem.motion.PopupMotion
import org.samesame.notes.core.designsystem.motion.SameSameMotion
import org.samesame.notes.feature.composer.menus.ObjectMoreMenu
import org.samesame.notes.feature.composer.menus.SelectionMoreMenu
import org.samesame.notes.feature.composer.menus.SelectionPill
import org.samesame.notes.feature.composer.pages.PageSorterScreen
import org.samesame.notes.feature.composer.pages.PageTemplateScreen
import org.samesame.notes.feature.composer.toolbar.Menu
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Full-screen composer pages opened from the overflow menu. */
private enum class ComposerPage { Template, Sorter }

/** What the page sorter's Share / Save as file does with its selected pages (the format dialog comes next). */
private enum class PageFileAction { Share, Save }

@Composable
fun ComposerRoute(
    noteId: String,
    onBack: () -> Unit,
    viewModel: ComposerViewModel = hiltViewModel<ComposerViewModel, ComposerViewModel.Factory>(
        key = noteId,
        creationCallback = { it.create(noteId) },
    ),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val s = session
    val locked by viewModel.isLocked.collectAsStateWithLifecycle()
    val lockedOut by viewModel.lockedOut.collectAsStateWithLifecycle()
    val favorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val meta by viewModel.meta.collectAsStateWithLifecycle()
    val relocked by viewModel.relocked.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(lockedOut) { if (lockedOut) onBack() }
    LockedNoteWindowGuard(locked)
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onStart() }
    if (relocked) {
        // The locked note was closed in the background: verify again before showing it (cancel leaves the editor).
        org.samesame.notes.feature.lock.LockAuthFlow(
            mode = org.samesame.notes.feature.lock.LockAuthMode.VerifyOnly,
            onToken = viewModel::reopenLocked,
            onCancel = onBack,
        )
    } else if (s == null) {
        // A plain page while the note loads (usually well under the open animation); the spinner only shows when
        // loading is slow, so opening a note doesn't flash a progress indicator.
        var slow by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(500); slow = true }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(), contentAlignment = Alignment.Center) {
            if (slow) CircularProgressIndicator()
        }
    } else {
        ComposerEditor(
            s,
            onBack = onBack,
            onStop = viewModel::onStop,
            exportNote = viewModel::export,
            settings = settings,
            locked = locked,
            onUnlock = viewModel::unlock,
            favorite = favorite,
            noteMeta = meta,
            onToggleFavorite = viewModel::toggleFavorite,
            onMoveToTrash = { viewModel.moveToTrash(onBack) },
            onLock = { token -> viewModel.lockNote(token, onBack) },
            insertPdf = viewModel::insertPdf,
            voiceNotices = viewModel.voiceNotices,
            voiceFile = viewModel::voiceFile,
            newDrawingSession = viewModel::newDrawingSession,
        )
    }
}

/**
 * While a locked note is open the window is secure: no Recents thumbnail, screenshots or screen recording
 * (security review M3; the reference sets the same flag on locked notes).
 */
@Composable
private fun LockedNoteWindowGuard(locked: Boolean) {
    var context = LocalContext.current
    while (context !is android.app.Activity && context is android.content.ContextWrapper) context = context.baseContext
    val window = (context as? android.app.Activity)?.window ?: return
    androidx.compose.runtime.DisposableEffect(window, locked) {
        if (locked) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (locked) window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@OptIn(FlowPreview::class)
@Composable
internal fun ComposerEditor(
    session: EditorSession,
    onBack: () -> Unit,
    onStop: () -> Unit,
    exportNote: suspend (ExportFormat, ExportOptions, OutputStream) -> ExportTarget,
    settings: AppSettings = AppSettings(),
    locked: Boolean = false,
    onUnlock: (org.samesame.notes.core.security.LockToken) -> Unit = { it.close() },
    favorite: Boolean = false,
    noteMeta: NoteMeta = NoteMeta(),
    onToggleFavorite: () -> Unit = {},
    onMoveToTrash: () -> Unit = {},
    onLock: (org.samesame.notes.core.security.LockToken) -> Unit = { it.close() },
    /** Insert > PDF: renders the file's pages after the page with the given id (returns the page count). */
    insertPdf: suspend (File, String) -> Int = { _, _ -> 0 },
    /** Outcome messages of voice recordings of this note. */
    voiceNotices: kotlinx.coroutines.flow.Flow<VoiceNotice> = kotlinx.coroutines.flow.emptyFlow(),
    /** The bundle file behind a voice object's media path (null when missing). */
    voiceFile: suspend (String) -> File? = { null },
    /** Builds the in-memory session of the drawing sheet; null leaves Insert > Drawing disabled. */
    newDrawingSession: ((kotlinx.coroutines.CoroutineScope, List<org.samesame.notes.core.ink.page.PageElement>) -> EditorSession)? = null,
) {
    var lockedSaveNotice by remember { mutableStateOf(false) }
    var unlockFlow by remember { mutableStateOf(false) }
    var unlockConfirm by remember { mutableStateOf(false) }
    var lockFlow by remember { mutableStateOf(false) }
    var trashConfirm by remember { mutableStateOf(false) }
    val chrome by session.chrome.collectAsStateWithLifecycle()
    val tools by session.tools.collectAsStateWithLifecycle()
    val doc by session.document.collectAsStateWithLifecycle()
    val selectionState = session.selectionState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val tb = chrome.toolbar
    val prefs = tools.prefs
    val dark = SameSameTheme.colors.isDark
    val darkAdjust = dark && settings.adjustColourInDarkMode && doc.style.adjustForDarkMode

    // ---- document, viewport, canvas source
    // Keyed on the page sizes too (v6): a page of its own size moves every page after it. The templates take part
    // because a ruled template changes the text line pitch (template rule alignment).
    val layout = remember(doc.pages.size, doc.layout, doc.pages.map { it.size }, doc.pages.map { it.template }) {
        DocumentLayout(doc.pages, doc.layout)
    }
    val latestLayout by rememberUpdatedState(layout)
    val viewport = remember(session) { Viewport() }
    val source = remember(session) { SessionPageSource(session) }
    val handlers = remember(session) {
        SessionToolHandlers(
            session,
            locator = PageLocator { p ->
                val d = session.holder.current
                val l = DocumentLayout(d.pages, d.layout)
                val i = l.pageAt(p) ?: return@PageLocator null
                PageHit(d.pages[i].id, l.pageOrigin(i))
            },
            handleRadiusPu = { 24f * density.density / viewport.scale },
        )
    }

    // ---- text layer
    val body = remember(session) {
        (session.body as? BodyTextBinding)?.body
            ?: BodyText(session.initialBody, TextRenderEnv()).also { session.bindBody(BodyTextBinding(it, session)) }
    }
    val textState = rememberRichTextEditorState(body, layout)
    textState.sensitive = locked
    // ---- text box editing: while a box is edited the toolbar, panels and Undo/Redo target its own body
    var editor by remember(session) { mutableStateOf<TextBoxEditor?>(null) }
    val latestEditor by rememberUpdatedState(editor)
    val activeBody = editor?.body ?: body
    val activeState = editor?.state ?: textState
    val fontSizeHold = remember { FontSizeHoldState() }
    // Picking a heading is a plain character style (size + bold), so the note format does not change.
    fun applyFontSizePick(pick: FontSizePick) {
        session.breakTypingBurst()
        val (size, bold) = when (pick) {
            is FontSizePick.Heading -> pick.heading.size.toFloat() to true
            FontSizePick.Body -> Limits.DEFAULT_FONT_SIZE to false
            is FontSizePick.Size -> pick.pt.toFloat() to false
        }
        activeBody.applyCharStyle { it.copy(fontSize = size, bold = bold) }
        session.dispatch(ToolbarIntent.ClosePanel)
    }
    val editorContext = LocalContext.current.applicationContext
    /** Ends the inline edit of a text box: one undo step, an empty box is removed (a fresh one is cancelled). */
    fun endTextBoxEdit() {
        val e = editor ?: return
        editor = null
        e.state.hideKeyboard()
        session.finishTextBoxEdit(e.pageId, e.id, e.toRichText(), e.height, e.fresh)
        e.dispose()
    }
    /** Starts editing the text box [id] of [pageId] in place (switches to text mode). */
    fun beginTextBoxEdit(pageId: String, id: org.samesame.notes.core.ink.page.ElementId, fresh: Boolean) {
        endTextBoxEdit()
        val f = (session.content(pageId)?.elements?.firstOrNull { it.id == id } as? ObjectElement)?.floating() ?: return
        val box = f.obj as? TextBoxObject ?: return
        session.clearSelection()
        editor = TextBoxEditor(pageId, id, fresh, box.text, f.rect, editorContext, darkAdjust).also { it.state.sensitive = locked }
        textState.hideKeyboard()
        if (session.chrome.value.toolbar.mode == EditorMode.Pen) session.dispatch(ToolbarIntent.ToggleHandwriting)
        session.dispatch(ToolbarIntent.ClosePanel)
    }
    /**
     * Selects a tapped object (image, drawing, text box) in place: frame, 8 handles, rotate knob and the options pill, while
     * the mode and toolbar stay as they are (selecting an object in the reference changes neither). In text mode the body gives up
     * the keyboard; presses on the frame then move / resize / rotate it through the selection handler.
     */
    fun selectTextBoxWithChrome(pageId: String, el: ObjectElement) {
        endTextBoxEdit()
        if (session.chrome.value.toolbar.mode == EditorMode.Text) textState.hideKeyboard()
        session.select(pageId, listOf(el))
    }
    /** Text mode: true when a press at [view] lands on the selected object's frame (body, handles, rotate knob). */
    fun grabsSelectedObject(view: Offset): Boolean {
        val sel = session.selection.current
        if (sel.frame == null) return false
        val pageId = sel.pageId ?: return false
        val index = session.holder.current.pages.indexOfFirst { it.id == pageId }
        if (index < 0) return false
        return handlers.selection.grabs(viewport.viewToDoc(view) - latestLayout.pageOrigin(index))
    }
    SideEffect { handlers.selection.allowLasso = { session.chrome.value.toolbar.mode == EditorMode.Pen } }
    LaunchedEffect(editor) {
        val e = editor ?: return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        runCatching { e.state.requestInput() }
    }
    LaunchedEffect(darkAdjust, editor) { editor?.setDark(darkAdjust) }
    DisposableEffect(session) { onDispose { endTextBoxEdit() } }
    // Settings: "Show links in notes" underlines links and opens them on tap. Web previews (`settings.showWebPreviews`)
    // has no consumer yet: web cards are not built.
    val appContext = LocalContext.current
    // Print (#60): the PDF export through the Android print framework; locked notes ask for the unlock first, like export.
    fun printNote() {
        if (locked) lockedSaveNotice = true
        else org.samesame.notes.feature.composer.print.NotePrint.print(appContext, session.holder.current.title) { out ->
            exportNote(org.samesame.notes.core.export.ExportFormat.PDF, org.samesame.notes.core.export.ExportOptions(), out)
        }
    }
    SideEffect {
        textState.showLinks = settings.showLinks
        textState.onLinkClick = { link ->
            runCatching {
                val intent = when (link.kind) {
                    TextLink.Kind.Date, TextLink.Kind.Time -> {
                        // Calendar insert, like the reference: ACTION_EDIT with an event mime type and begin/end extras.
                        val parsed = org.samesame.notes.core.richtext.DateTimeLinkParser.parse(link.target)
                        if (parsed == null) null else {
                            val cal = java.util.Calendar.getInstance()
                            val hasDate = parsed.year != null && parsed.month != null && parsed.day != null
                            if (hasDate) cal.set(parsed.year!!, parsed.month!! - 1, parsed.day!!)
                            val timed = parsed.hour != null
                            if (timed) {
                                cal.set(java.util.Calendar.HOUR_OF_DAY, parsed.hour!!)
                                cal.set(java.util.Calendar.MINUTE, parsed.minute ?: 0)
                            } else {
                                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                                cal.set(java.util.Calendar.MINUTE, 0)
                            }
                            android.content.Intent(android.content.Intent.ACTION_EDIT)
                                .setType("vnd.android.cursor.item/event")
                                .putExtra("beginTime", cal.timeInMillis)
                                .putExtra("endTime", cal.timeInMillis + if (timed) 60 * 60 * 1000L else 24 * 60 * 60 * 1000L)
                                .putExtra("allDay", !timed)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    TextLink.Kind.Phone -> android.content.Intent(
                        android.content.Intent.ACTION_DIAL, android.net.Uri.parse(link.target),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    TextLink.Kind.Email -> android.content.Intent(
                        android.content.Intent.ACTION_SENDTO, android.net.Uri.parse(link.target),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    TextLink.Kind.Url -> android.content.Intent(
                        android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link.target),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent != null) appContext.startActivity(intent)
            }
        }
    }
    val textMode = tb.mode == EditorMode.Text
    // #26: the "Tap Title..." tip is a one-time centred guide, not an inline hint. the reference (composer GuideTextView) shows it
    // only for an empty note with auto save on, and records it as seen once it is dismissed by an edit or the note is left.
    SideEffect {
        textState.enabled = textMode && editor == null
        textState.hint = null
    }
    val bodyGuideVisible = !prefs.bodyGuideSeen && body.length == 0 && session.holder.current.title.isEmpty() &&
        !chrome.undo.canUndo && settings.autoSaveNotes
    val latestGuideVisible by rememberUpdatedState(bodyGuideVisible)
    // Observe `revision` from a snapshot flow, never from composition: it bumps on every text/selection change
    // (and on the initial dark-colour setup right after the first frame), which would recompose this whole
    // screen once on open and again on every keystroke just to recompute the guide condition.
    LaunchedEffect(textState, session) {
        snapshotFlow { textState.revision; body.length > 0 || chrome.undo.canUndo }
            .distinctUntilChanged()
            .collect { hasContent ->
                if (hasContent && !session.tools.value.prefs.bodyGuideSeen) {
                    session.toolController.updatePrefs { it.copy(bodyGuideSeen = true) }
                }
            }
    }
    DisposableEffect(session) {
        onDispose {
            if (latestGuideVisible) session.toolController.updatePrefs { it.copy(bodyGuideSeen = true) }
        }
    }
    // A fresh (empty) note opens in edit mode with the keyboard up (item 12); existing notes open in reading mode
    // and this never runs there. Hardware keys reach the body once the caret is placed.
    LaunchedEffect(textState, session) {
        if (body.length == 0 && session.holder.current.title.isEmpty() && session.holder.current.pages.size == 1) {
            androidx.compose.runtime.withFrameNanos { }
            if (session.chrome.value.toolbar.mode == EditorMode.Text) runCatching { textState.requestInput() }
        }
    }
    // Touch to edit (view mode tap): once Text mode is composed and the transform follows the new canvas size, put the
    // caret at the tapped document point and raise the keyboard (onTap places the caret and requests input).
    var pendingEditTap by remember { mutableStateOf<Offset?>(null) }
    /** The last press in the view state came from a stylus (or its eraser end): its tap edits in pen mode. */
    var lastViewDownStylus by remember { mutableStateOf(false) }
    SideEffect { textState.readOnlySelect = tb.mode == EditorMode.Reading && tb.viewMode }
    // A page with a picture as its background (imported PDF) stays light in a dark note, so its text keeps the light colours too.
    SideEffect {
        textState.engine.lightPage = { i -> darkAdjust && session.holder.current.pages.getOrNull(i)?.let { !adjustsForDark(it, true) } == true }
    }
    LaunchedEffect(textMode, pendingEditTap) {
        val d = pendingEditTap ?: return@LaunchedEffect
        if (!textMode) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        runCatching { textState.onTap(viewport.docToView(d)) }
        pendingEditTap = null
    }
    LaunchedEffect(darkAdjust, body) {
        body.env.colorTransform = if (darkAdjust) { c -> DarkModeInk.invert(c) } else { c -> c }
        textState.colors = if (darkAdjust) RichTextColors.Dark else RichTextColors.Light
    }
    LaunchedEffect(textState, viewport) {
        snapshotFlow { ViewTransform(viewport.scale, viewport.origin.x, viewport.origin.y) }
            .collect { textState.transform = it }
    }
    // Pages the text needs beyond the current count are appended (no history entry of their own). Pages appended
    // this way are dropped again (while still empty) once the text no longer reaches them, e.g. after deleting lines.
    LaunchedEffect(textState, session) {
        val autoAdded = HashSet<String>()
        snapshotFlow { textState.revision; textState.engine.requiredPageCount }.distinctUntilChanged().collect { required ->
            val pages = session.holder.current.pages
            if (required > pages.size) {
                val last = pages.last()
                val extra = List(required - pages.size) { last.copy(id = UUID.randomUUID().toString(), isBookmarked = false, pdf = null) }
                autoAdded += extra.map { it.id }
                session.setPageSpecs(pages + extra)
            } else if (required < pages.size && !latestLayout.isInfinite) {
                var keep = pages.size
                while (keep > required && keep > 1) {
                    val id = pages[keep - 1].id
                    if (id !in autoAdded || session.pages.content(id)?.elements?.isEmpty() != true) break
                    keep--
                }
                if (keep < pages.size) {
                    autoAdded -= pages.drop(keep).map { it.id }.toSet()
                    session.setPageSpecs(pages.take(keep))
                }
            }
        }
    }
    // Keep the caret on screen while typing / when the keyboard resizes the canvas.
    LaunchedEffect(textState, viewport) {
        snapshotFlow { Triple(textState.revision, viewport.size, textState.isFocused) }.collect { (_, _, focused) ->
            if (!focused || !textState.enabled || viewport.size.height == 0) return@collect
            val c = textState.engine.caret(textState.body.selection.min)
            val v = viewport.visibleDoc
            val margin = 48f
            if (latestLayout.horizontal) {
                if (c.x > v.right - margin) viewport.scrollTo(Offset(c.x - v.width + margin, viewport.scroll.y))
                else if (c.x < v.left + margin) viewport.scrollTo(Offset(c.x - margin, viewport.scroll.y))
            } else {
                if (c.bottom > v.bottom - margin) viewport.scrollTo(Offset(viewport.scroll.x, c.bottom - v.height + margin))
                else if (c.top < v.top + margin) viewport.scrollTo(Offset(viewport.scroll.x, c.top - margin))
            }
        }
    }
    var textCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // The canvas area in the root box's coordinates; read by the selection pill in its layout phase only.
    val canvasBounds = remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // Keep the caret of an edited text box on screen while typing / when the keyboard resizes the canvas.
    LaunchedEffect(editor, viewport) {
        val e = editor ?: return@LaunchedEffect
        snapshotFlow { Triple(e.state.revision, viewport.size, e.state.isFocused) }.collect { (_, _, focused) ->
            if (!focused || viewport.size.height == 0) return@collect
            val index = session.holder.current.pages.indexOfFirst { it.id == e.pageId }
            if (index < 0 || index >= latestLayout.pageCount) return@collect
            val origin = latestLayout.pageOrigin(index)
            val c = e.state.engine.caret(e.body.selection.min)
            val v = viewport.visibleDoc
            val margin = 48f
            val top = origin.y + e.rect.top + c.top; val bottom = origin.y + e.rect.top + c.bottom
            val x = origin.x + e.rect.left + c.x
            if (latestLayout.horizontal) {
                if (x > v.right - margin) viewport.scrollTo(Offset(x - v.width + margin, viewport.scroll.y))
                else if (x < v.left + margin) viewport.scrollTo(Offset(x - margin, viewport.scroll.y))
            } else {
                if (bottom > v.bottom - margin) viewport.scrollTo(Offset(viewport.scroll.x, bottom - v.height + margin))
                else if (top < v.top + margin) viewport.scrollTo(Offset(viewport.scroll.x, top - margin))
            }
        }
    }
    /** The text box under a view point (page id and element), or null. */
    fun textBoxAtView(p: Offset): Pair<String, ObjectElement>? {
        val d = viewport.viewToDoc(p)
        val l = latestLayout
        val index = l.pageAt(d) ?: return null
        val pageId = session.holder.current.pages.getOrNull(index)?.id ?: return null
        val el = session.objectAt(pageId, d - l.pageOrigin(index)) ?: return null
        return if (el.floating()?.obj is TextBoxObject) pageId to el else null
    }

    /**
     * A placed image or drawing under a view point (page id and element), or null. An image on the bottom layer yields to the
     * body text lying on it: a tap on a line of text still puts the caret there; a tap in the gaps selects the image.
     */
    fun imageAtView(p: Offset): Pair<String, ObjectElement>? {
        val d = viewport.viewToDoc(p)
        val l = latestLayout
        val index = l.pageAt(d) ?: return null
        val pageId = session.holder.current.pages.getOrNull(index)?.id ?: return null
        val el = session.objectAt(pageId, d - l.pageOrigin(index)) ?: return null
        val f = el.floating() ?: return null
        if (f.obj !is org.samesame.notes.core.model.document.ImageObject && f.obj !is PaintingObject) return null
        if (f.wrap == TextWrap.Behind && textState.engine.isOnText(d.x, d.y)) return null
        return pageId to el
    }

    /** The object id of the inline (anchored) object at body [offset], or null (one U+FFFC character per object). */
    fun inlineObjectIdAt(offset: Int): String? {
        val e = body.editable
        if (offset < 0 || offset >= e.length || e[offset] != OBJECT_CHAR) return null
        return e.getSpans(offset, offset + 1, InlineObjectSpan::class.java)
            .firstOrNull { e.getSpanStart(it) <= offset && e.getSpanEnd(it) > offset }?.objectId
    }

    /**
     * The body offset of an inline (anchored) object under a view point, or null. Inline objects are one U+FFFC
     * character carrying an [InlineObjectSpan]; selecting that character brings the text toolbar with Remove anchor and
     * Delete, so an anchored image can be selected again like text.
     */
    fun inlineObjectAtView(p: Offset): Int? {
        if (body.length == 0) return null
        val d = viewport.viewToDoc(p)
        return AnchorToText.objectOffsetAt(
            hit = textState.engine.hitTest(d.x, d.y),
            length = body.length,
            isObject = { inlineObjectIdAt(it) != null },
            contains = { o ->
                val b = textState.engine.selectionBounds(o, o + 1)
                d.x >= b.left && d.x <= b.right && d.y >= b.top && d.y <= b.bottom
            },
        )
    }

    /**
     * A tap in the view state (reference capture, reading-mode/findings.md): a checklist box toggles and stays in view, a
     * link opens, an image enters edit with the caret on the paragraph after it (not selected), anything else enters edit
     * with the caret at the tapped character. A tap while a word is selected drops the selection and enters edit too.
     */
    fun viewTap(p: Offset) {
        val d = viewport.viewToDoc(p)
        textState.engine.checkboxAt(d.x, d.y)?.let { body.toggleChecked(it); return }
        if (textState.onReadTap(p)) return
        if (lastViewDownStylus) {
            // A pen tap: edit in pen mode (handwriting), no caret / keyboard.
            textState.dismissSelectionUi()
            session.dispatch(ToolbarIntent.ExitViewWithPen)
            return
        }
        val target = imageAtView(p)?.let { (pageId, el) ->
            val index = session.holder.current.pages.indexOfFirst { it.id == pageId }
            val f = el.floating()
            if (index < 0 || f == null) d
            else Offset(d.x, latestLayout.pageOrigin(index).y + f.rotatedBounds.bottom + 1f)
        } ?: d
        textState.dismissSelectionUi()
        body.setSelection(body.selection.max)
        pendingEditTap = target
        session.dispatch(ToolbarIntent.ExitReading)
    }
    SideEffect { textState.onReadOnlyTap = ::viewTap }

    // ---- view state restore / save
    fun saveViewState() {
        val l = latestLayout
        if (viewport.size.width == 0 || l.pageCount == 0) return
        val idx = l.nearestPage(viewport.scroll) ?: return
        val r = l.pageRects[idx]
        val vs = ViewState(idx, (if (l.horizontal) viewport.scroll.x - r.left else viewport.scroll.y - r.top), viewport.zoom)
        if (vs != session.holder.current.viewState) session.setViewState(vs)
    }
    LaunchedEffect(session, viewport) {
        snapshotFlow { viewport.size.width > 0 && viewport.content.width > 0f }.first { it }
        session.holder.current.viewState?.let { vs ->
            val l = latestLayout
            val r = l.pageRects.getOrNull(vs.pageIndex.coerceIn(0, (l.pageCount - 1).coerceAtLeast(0)))
            if (r != null) {
                viewport.restore(if (l.horizontal) Offset(r.left + vs.offsetInPage, 0f) else Offset(0f, r.top + vs.offsetInPage), vs.zoom)
            }
        }
        snapshotFlow { viewport.scroll to viewport.zoom }.debounce(600).collect { saveViewState() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // a recording keeps going in its service; playback pauses, but not across a rotation (the player is process-wide)
        VoicePlaybackHub.peek()?.onEditorStopped(appContext.isChangingConfigurations())
        endTextBoxEdit()
        saveViewState()
        onStop()
    }

    // ---- save errors
    val snackbar = remember { SnackbarHostState() }
    val saveFailed = stringResource(R.string.feature_composer_save_failed)
    val pdfFailed = stringResource(R.string.feature_composer_pdf_failed)
    val cameraFailed = stringResource(R.string.feature_composer_camera_failed)
    LaunchedEffect(session) { session.saveErrors.collect { snackbar.showSnackbar(saveFailed) } }

    // ---- mode transitions
    var prevMode by remember { mutableStateOf(tb.mode) }
    LaunchedEffect(tb.mode) {
        if (tb.mode != EditorMode.Text) {
            // An object selected in text mode doesn't follow into pen mode (its frame would sit under the pen).
            if (prevMode == EditorMode.Text) session.clearSelection()
            endTextBoxEdit()
            textState.hideKeyboard()
            textState.dismissSelectionUi()
            focusManager.clearFocus()
        } else if (prevMode == EditorMode.Pen) {
            session.clearSelection()
            runCatching { textState.requestInput() }
        }
        prevMode = tb.mode
    }

    // ---- back chain: panel -> menu -> selection -> full screen -> reading -> leave
    // Settings: with "Auto save notes" off, leaving a note with unsaved edits asks Save / Discard; with
    // "Block Back button while editing" on, the Back key does nothing once nothing is left to close.
    var leaveDialog by remember { mutableStateOf(false) }
    fun leaveOrAsk(outcome: BackOutcome) {
        if (outcome == BackOutcome.AskSaveOrDiscard) leaveDialog = true else onBack()
    }
    val backAction = ToolbarReducer.backAction(tb, chrome.hasSelection)
    BackHandler(enabled = true) {
        when (val outcome = BackPolicy.systemBack(backAction, settings.blockBackKey, settings.autoSaveNotes, session.hasUnsavedChanges())) {
            BackOutcome.Chain ->
                if (backAction == BackAction.ClearSelection) session.clearSelection()
                else session.dispatch(ToolbarIntent.Back(chrome.hasSelection))
            BackOutcome.Ignore -> Unit
            else -> leaveOrAsk(outcome)
        }
    }
    // Back while a text box is edited ends the edit first (the keyboard closes with it); panels and menus close before.
    BackHandler(enabled = editor != null && tb.panel == null && tb.menu == null) { endTextBoxEdit() }
    if (leaveDialog) {
        AppDialog(
            onDismissRequest = { leaveDialog = false },
            dismissLabel = stringResource(R.string.feature_composer_leave_discard),
            onDismissClick = {
                leaveDialog = false
                session.discardChanges()
                onBack()
            },
            confirmLabel = stringResource(R.string.feature_composer_leave_save),
            onConfirm = {
                leaveDialog = false
                scope.launch {
                    session.saveNow()
                    onBack()
                }
            },
            title = stringResource(R.string.feature_composer_leave_title),
            atBottom = true,
        )
    }

    // ---- ink input
    val inputSettings = InputSettings(
        mode = when (tb.mode) { EditorMode.Text -> InputMode.Text; EditorMode.Pen -> InputMode.Pen; EditorMode.Reading -> InputMode.Reading },
        tool = when (tb.tool) {
            PenModeTool.Pen -> InputTool.Pen
            PenModeTool.Highlighter -> InputTool.Highlighter
            PenModeTool.Eraser -> InputTool.Eraser
            PenModeTool.Selection -> InputTool.Selection
        },
        // On until a stylus has been seen (doc 3); the first stylus event persists that and turns it off (EditorPreferences.withStylusSeen).
        fingerDrawing = prefs.fingerDrawing,
        // The lock only applies while the Zoom lock add-on is on.
        zoomLock = prefs.zoomLock && settings.toolbarZoomLock,
    )
    val penStyle = remember(tb.tool, prefs.penType, prefs.penLevel, prefs.penColor, prefs.penPressureSensitive, prefs.highlighterLevel, prefs.highlighterColor, prefs.highlighterOpacity, prefs.highlighterTip) {
        PenStyles.current(tb.tool, prefs)
    }
    val brush = remember(penStyle) { penStyle.brush() }

    // ---- title
    var title by remember(session) { mutableStateOf(chrome.title) }

    // ---- easy writing pad (#79)
    var easyPadOpen by remember { mutableStateOf(false) }
    LaunchedEffect(tb.mode, settings.toolbarEasyWritingPad) { if (tb.mode != EditorMode.Pen || !settings.toolbarEasyWritingPad) easyPadOpen = false }
    val padCaret = textState.body.selection.min
    val padKey = padCaret to textState.body.length
    val padSlot: PadSlot? = if (easyPadOpen && layout.pageCount > 0) {
        val c = textState.engine.caret(padCaret)
        val page = c.pageIndex.coerceIn(0, layout.pageCount - 1)
        val content = layout.contentRect(page); val pageRect = layout.pageRect(page)
        PadSlot(
            pageIndex = page, x = c.x - pageRect.left, lineTop = c.top - pageRect.top, lineHeight = (c.bottom - c.top).coerceAtLeast(1f),
            contentLeft = content.left - pageRect.left, contentRight = content.right - pageRect.left,
        )
    } else null
    // Keep the cursor line above the pad: the canvas shrinks when the pad opens, so scroll the caret into view.
    LaunchedEffect(easyPadOpen, viewport, padKey) {
        if (!easyPadOpen) return@LaunchedEffect
        snapshotFlow { viewport.size }.first { it.height > 0 }
        val c = textState.engine.caret(padCaret)
        val v = viewport.visibleDoc
        val margin = 48f
        if (!latestLayout.horizontal) {
            if (c.bottom > v.bottom - margin) viewport.scrollTo(Offset(viewport.scroll.x, c.bottom - v.height + margin))
            else if (c.top < v.top + margin) viewport.scrollTo(Offset(viewport.scroll.x, c.top - margin))
        }
    }

    // ---- erase all
    var eraseAllOpen by remember { mutableStateOf(false) }
    LaunchedEffect(tb.panel) { if (tb.panel != Panel.Eraser) eraseAllOpen = false }
    val currentPage by remember(viewport) {
        derivedStateOf {
            val v = viewport.visibleDoc
            val l = latestLayout
            l.visiblePages(v).maxByOrNull { i ->
                val r = l.pageRects[i]
                val x = r.intersect(v)
                if (x.width <= 0f || x.height <= 0f) 0f else x.width * x.height
            } ?: 0
        }
    }
    fun eraseAll(all: Boolean) {
        eraseAllOpen = false
        session.dispatch(ToolbarIntent.ClosePanel)
        val pages = session.holder.current.pages
        val ids = if (all) pages.map { it.id } else listOfNotNull(pages.getOrNull(currentPage)?.id)
        scope.launch {
            ids.forEach { session.pages.await(it) }
            session.group { ids.forEach { id -> session.editElements(id) { emptyList() } } }
        }
    }

    // ---- full screen (R3): the status bar is hidden with the app bar and toolbar (reference #61: only the exit disc remains)
    val hostView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(tb.fullScreen, hostView) {
        val window = (hostView.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, hostView) }
        if (tb.fullScreen) controller?.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        onDispose { controller?.show(androidx.core.view.WindowInsetsCompat.Type.statusBars()) }
    }

    // ---- WP-J2: insert sources, menus, full-screen pages and dialogs
    var pageScreen by remember { mutableStateOf<ComposerPage?>(null) }
    var saveAsOpen by remember { mutableStateOf(false) }
    var shareMenuOpen by remember { mutableStateOf(false) }
    var tagsOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // ---- in-note search (R1): a debounced search over the body text; hits painted by the text layer
    val searchNav = remember(session) { SearchNavigator() }
    var searchTick by remember { mutableIntStateOf(0) } // bumps when the cursor / hit list changed
    fun showHit(hit: IntRange?) {
        textState.searchMatches = searchNav.matches
        textState.searchCurrent = searchNav.current
        searchTick++
        if (hit == null) return
        // Bring the hit into view: vertical notes put it a third from the top, horizontal ones centre it.
        val c = textState.engine.caret(hit.first)
        val v = viewport.visibleDoc
        val margin = 48f
        if (latestLayout.horizontal) {
            if (c.x < v.left + margin || c.x > v.right - margin) viewport.scrollTo(Offset(c.x - v.width / 2f, viewport.scroll.y))
        } else if (c.top < v.top + margin || c.bottom > v.bottom - margin) {
            viewport.scrollTo(Offset(viewport.scroll.x, c.top - v.height / 3f))
        }
    }
    var searchPending by remember { mutableStateOf(false) }
    fun runSearch() {
        searchPending = false
        showHit(searchNav.search(body.editable, searchQuery))
    }
    LaunchedEffect(searchOpen, searchQuery) {
        if (!searchOpen) {
            searchNav.clear()
            textState.searchMatches = emptyList()
            textState.searchCurrent = -1
            searchPending = false
            return@LaunchedEffect
        }
        if (searchQuery.isEmpty()) { runSearch(); return@LaunchedEffect }
        searchPending = true
        kotlinx.coroutines.delay(300) // SEARCH_DELAY
        runSearch()
    }
    // Back closes the search bar first (registered last, so it sits on top of the back chain).
    BackHandler(enabled = searchOpen) { searchOpen = false; searchQuery = "" }
    // Save as file: the format and the page subset (null = the whole note) until the file picker returns.
    var pendingExport by remember { mutableStateOf<Pair<ExportFormat, Set<String>?>?>(null) }
    var pageFileAction by remember { mutableStateOf<Pair<PageFileAction, Set<String>>?>(null) }
    val context = LocalContext.current
    val images = remember(session) {
        ObjectImageCache(scope, session::loadMediaBytes) { bytes ->
            withContext(Dispatchers.IO) { org.samesame.notes.core.ink.media.BoundedBitmaps.decode(bytes)?.asImageBitmap() }
        }
    }
    // Objects anchored to the text (inline): drawn from the same bitmap cache; a bitmap landing redraws the text.
    val latestDark by rememberUpdatedState(dark)
    DisposableEffect(body, images) {
        body.env.objectProvider = InlineObjectPainter(
            lookup = { id -> session.holder.inlineObject(id) },
            image = images::image,
            painters = ObjectPainters(),
            dark = { latestDark },
        )
        body.invalidateObjects()
        onDispose { }
    }
    LaunchedEffect(body, images) {
        snapshotFlow { images.version }.collect {
            if (session.holder.current.inlineObjects.isNotEmpty()) body.invalidateDrawing()
        }
    }
    // PDF page backgrounds are big bitmaps: only the most recent few stay decoded.
    val backgrounds = remember(session) {
        ObjectImageCache(scope, session::loadMediaBytes, maxEntries = 8) { bytes ->
            withContext(Dispatchers.IO) { org.samesame.notes.core.ink.media.BoundedBitmaps.decode(bytes)?.asImageBitmap() }
        }
    }
    val createDocument = rememberLauncherForActivityResult(org.samesame.notes.feature.share.CreateTypedDocument()) { uri ->
        val pending = pendingExport
        pendingExport = null
        if (uri != null && pending != null) {
            val (format, pageIds) = pending
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out -> exportNote(format, ExportOptions(pageIds = pageIds), out) }
                            ?: error("cannot open output")
                    }
                }
                if (result.isFailure) snackbar.showSnackbar(saveFailed)
            }
        }
    }
    fun startSaveAs(format: ExportFormat, pageIds: Set<String>? = null) {
        val infinite = doc.layout.mode == PageMode.Infinite
        val pageCount = pageIds?.let { ids -> doc.pages.count { it.id in ids } } ?: doc.pages.size
        val target = expectedTarget(format, ExportOptions(pageIds = pageIds), infinite, pageCount)
        pendingExport = format to pageIds
        createDocument.launch(target.fileName(title) to target.mimeType)
    }
    // Share: render the note into the share cache, then open the system share sheet.
    val shareChooserTitle = stringResource(R.string.feature_composer_ov_share)
    fun shareAs(format: ExportFormat, pageIds: Set<String>? = null) {
        val infinite = doc.layout.mode == PageMode.Infinite
        val pageCount = pageIds?.let { ids -> doc.pages.count { it.id in ids } } ?: doc.pages.size
        val target = expectedTarget(format, ExportOptions(pageIds = pageIds), infinite, pageCount)
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val file = ShareFiles.newCacheFile(context, target.fileName(title))
                    val written = file.outputStream().buffered().use { exportNote(format, ExportOptions(pageIds = pageIds), it) }
                    // The exporter knows the real type (an Images export may be one PNG or a zip).
                    val real = if (written.extension == target.extension) file
                    else File(file.parentFile, written.fileName(title)).also { file.renameTo(it) }
                    real to written.mimeType
                }
            }
            result.onSuccess { (file, mime) -> ShareFiles.shareFile(context, file, mime, shareChooserTitle) }
            if (result.isFailure) snackbar.showSnackbar(saveFailed)
        }
    }
    // Image and Camera (`#53`) share this path: the picture is inserted on the current page, centred in the viewport.
    // [afterInsert] runs once the bytes are read (the camera deletes its capture then).
    fun insertImageFrom(uri: Uri, afterInsert: () -> Unit = {}) {
        val pageIndex = currentPage
        val pageId = doc.pages.getOrNull(pageIndex)?.id
        if (pageId == null) { afterInsert(); return }
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                if (bytes != null) {
                    val (w, h) = withContext(Dispatchers.IO) {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                        o.outWidth to o.outHeight
                    }
                    val aspect = if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 1f
                    val widthPu = Limits.PAGE_WIDTH_PU * 0.7f
                    val center = viewport.visibleDoc.center - latestLayout.pageOrigin(pageIndex)
                    val ext = context.contentResolver.getType(uri)?.substringAfterLast('/')
                        ?.let { if (it == "jpeg") "jpg" else it } ?: "png"
                    session.insertImage(pageId, bytes, ext, widthPu, widthPu / aspect, center)
                }
            } finally {
                afterInsert()
            }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) insertImageFrom(uri)
    }
    // Insert > Camera: the system camera writes a JPEG into cache/share/camera through the share FileProvider. The camera
    // app takes the picture, so the app needs no CAMERA permission. The path is kept so a process death still finds it.
    var cameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = cameraFilePath?.let(::File)
        cameraFilePath = null
        if (file == null) return@rememberLauncherForActivityResult
        if (saved && file.length() > 0) insertImageFrom(ShareFiles.uriFor(context, file)) { file.delete() }
        else file.delete()
    }
    fun takePicture() {
        val dir = File(context.cacheDir, "share/camera").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
        cameraFilePath = file.path
        try {
            cameraCapture.launch(ShareFiles.uriFor(context, file))
        } catch (e: ActivityNotFoundException) {
            cameraFilePath = null
            scope.launch { snackbar.showSnackbar(cameraFailed) }
        }
    }
    // Insert > Camera (ux6 item 4): the in-app camera sheet over the bottom of the screen. Needs the CAMERA permission;
    // denied -> the system camera (which itself refuses while our declared permission is denied: then a snackbar
    // points to the app settings). A device without a camera / a camera that won't open -> the system camera.
    var cameraSheetOpen by rememberSaveable { mutableStateOf(false) }
    val cameraPermissionNeeded = stringResource(R.string.feature_composer_camera_permission_needed)
    val openSettingsLabel = stringResource(R.string.feature_composer_open_settings)
    fun systemCamera() {
        try {
            takePicture()
        } catch (_: SecurityException) {
            cameraFilePath = null
            scope.launch {
                val r = snackbar.showSnackbar(cameraPermissionNeeded, actionLabel = openSettingsLabel)
                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null)),
                    )
                }
            }
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraSheetOpen = true else systemCamera()
    }
    fun openCamera() {
        val pm = context.packageManager
        when {
            !pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY) -> systemCamera()
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED -> cameraSheetOpen = true
            else -> cameraPermission.launch(android.Manifest.permission.CAMERA)
        }
    }
    // Insert > PDF: the picked PDF is copied to the cache (the renderer needs a seekable file), its pages become page
    // backgrounds after the current page as one undo step, like the page sorter's paste.
    fun insertPdfFrom(uri: Uri) {
        val afterPageId = doc.pages.getOrNull(currentPage)?.id ?: return
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "pdf-insert").apply { deleteRecursively(); mkdirs() }
                    File(dir, "insert.pdf").also { target ->
                        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no stream")
                        input.use { source -> target.outputStream().use { source.copyTo(it) } }
                    }
                }
                try {
                    insertPdf(file, afterPageId)
                } finally {
                    withContext(Dispatchers.IO) { file.delete() }
                }
            } catch (e: PdfImportException) {
                snackbar.showSnackbar(pdfFailed)
            } catch (e: IOException) {
                snackbar.showSnackbar(pdfFailed)
            }
        }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) insertPdfFrom(uri)
    }
    fun applyTemplate(template: PageTemplate, applyAll: Boolean) {
        val ref = TemplateRef.Builtin(template)
        val pages = if (applyAll) session.holder.current.pages.map { it.copy(template = ref) }
        else session.holder.current.pages.mapIndexed { i, p -> if (i == currentPage) p.copy(template = ref) else p }
        session.setPageSpecs(pages)
    }
    fun reorder(from: Int, to: Int) {
        val pages = session.holder.current.pages.toMutableList()
        if (from !in pages.indices || to !in pages.indices) return
        pages.add(to, pages.removeAt(from))
        session.reorderPages(pages)
    }
    fun addBlankPage() {
        val last = session.holder.current.pages.lastOrNull()
        session.addPage(last?.follower(UUID.randomUUID().toString()) ?: PageSpec(id = UUID.randomUUID().toString()))
    }
    fun closeMenu() = session.dispatch(ToolbarIntent.CloseMenu)
    val infinite = doc.layout.mode == PageMode.Infinite
    // Pull past the end of the last page to add one (Pages mode, editing): one undo step, same template/size as the last.
    val pullToAdd = remember(session) { PullToAddPageState() }
    pullToAdd.onCommit = {
        val expected = session.holder.current.pages.size + 1
        addBlankPage()
        // The sheet covers the canvas, so insertion and the scroll below are invisible. Two frames let the session's
        // document flow and the recomposed layout land (as before); if the layout is somehow still stale, wait up to
        // two more so the scroll lands on the real last page instead of the old one.
        repeat(2) { androidx.compose.runtime.withFrameNanos { } }
        var guard = 0
        while (latestLayout.pageCount < expected && guard++ < 2) {
            androidx.compose.runtime.withFrameNanos { }
        }
        val l = latestLayout
        if (l.pageCount > 0) {
            val o = l.pageOrigin(l.pageCount - 1)
            viewport.scrollTo(if (l.horizontal) Offset(o.x, viewport.scroll.y) else Offset(viewport.scroll.x, o.y))
        }
    }

    fun undo() { val e = editor; if (e != null) e.undo() else scope.launch { session.undo() } }
    fun redo() { val e = editor; if (e != null) e.redo() else scope.launch { session.redo() } }
    // Insert > Text box: an empty box at the 16 dp margin of the current page, edited at once (the reference switches to text mode too).
    // The Insert menu has no paste position, so the box takes the margin corner (the reference ContextMenuAddTextBox fallback).
    fun insertTextBox() {
        val pageId = doc.pages.getOrNull(currentPage)?.id ?: return
        endTextBoxEdit()
        session.insertTextBox(pageId, textBoxInsertTopLeft(pastePu = null))?.let { beginTextBoxEdit(pageId, it, fresh = true) }
    }

    // ---- drawing (O6): Insert > Drawing and re-edit open the full-screen sheet; its result is one undo step on the page
    var drawing by remember { mutableStateOf<DrawingRequest?>(null) }
    val drawingSaveFailed = stringResource(R.string.feature_composer_drawing_save_failed)
    fun openDrawingSheet() {
        val pageIndex = currentPage
        val pageId = doc.pages.getOrNull(pageIndex)?.id ?: return
        endTextBoxEdit()
        session.clearSelection()
        val center = viewport.visibleDoc.center - latestLayout.pageOrigin(pageIndex)
        drawing = DrawingRequest(pageId, null, null, center)
    }
    /** Re-edit of the single selected painting (menu "Drawing" or a tap on it): same object, same place. */
    fun editSelectedDrawing(pageId: String, id: org.samesame.notes.core.ink.page.ElementId) {
        val painting = (session.content(pageId)?.elements?.firstOrNull { it.id == id } as? ObjectElement)?.floating()?.obj as? PaintingObject ?: return
        endTextBoxEdit()
        session.clearSelection()
        drawing = DrawingRequest(pageId, id, painting, Offset.Zero)
    }
    SideEffect { handlers.selection.onSelectedPaintingTap = ::editSelectedDrawing }
    // A second tap on the already-selected text box enters editing (the first tap selected it).
    SideEffect { handlers.selection.onSelectedTextBoxTap = { pageId, id -> beginTextBoxEdit(pageId, id, fresh = false) } }
    fun finishDrawing(req: DrawingRequest, result: DrawingResult?) {
        drawing = null
        if (result == null) return
        scope.launch {
            val ok = if (req.elementId == null) {
                val id = session.insertDrawing(
                    req.pageId, result.thumbnail, result.ink, result.content, req.centerPu,
                    latestLayout.pageSize(session.holder.current.pages.indexOfFirst { it.id == req.pageId }.coerceAtLeast(0)).let { SizePu(it.width, it.height) },
                )
                if (id != null) {
                    // the reference leaves the new painting selected (frame + menu) in the current mode; in pen mode the
                    // selection tool takes over so the frame can be moved.
                    val t = session.chrome.value.toolbar
                    if (t.mode == EditorMode.Pen && t.tool != PenModeTool.Selection) session.dispatch(ToolbarIntent.TapTool(PenModeTool.Selection))
                    session.content(req.pageId)?.elements?.firstOrNull { it.id == id }?.let { session.select(req.pageId, listOf(it)) }
                }
                id != null
            } else session.replaceDrawing(req.pageId, req.elementId, result.thumbnail, result.ink, result.content)
            if (!ok) snackbar.showSnackbar(drawingSaveFailed)
        }
    }

    // ---- voice recording (O5): recorder bar, play panel, Insert > Voice recording
    val recorder = remember { VoiceRecordingHub.get(context) }
    val player = remember { org.samesame.notes.feature.composer.voice.VoiceLog.init(context); VoicePlaybackHub.get() }
    val recState by recorder.state.collectAsStateWithLifecycle()
    // Only whether a recording is open is read here; the position ticks are collected inside the voice composables.
    val playOpenFlow = remember(player) { player.state.map { it.objectId != null }.distinctUntilChanged() }
    @Suppress("StateFlowValueCalledInComposition") // seed only; later values come from the flow
    val playOpen by playOpenFlow.collectAsStateWithLifecycle(initialValue = player.state.value.objectId != null)
    val recordingHere = (recState as? RecorderState.Active)?.takeIf { it.noteId == session.noteId }
    var recorderMinimized by rememberSaveable { mutableStateOf(false) }
    // Note-level recordings: the collapsed recording bar (VOICEINIT), the extended player and the "Recorded audio" list.
    val recordingRows = doc.recordings
    val hasRecordings = recordingRows.isNotEmpty()
    val totalRecordMs = recordingRows.sumOf { it.durationMs }
    var voiceList by remember { mutableStateOf(VoiceListState()) }
    var voiceListOpen by rememberSaveable { mutableStateOf(false) }
    var voiceExtendOpen by rememberSaveable { mutableStateOf(false) }
    var voiceBarMinimized by rememberSaveable { mutableStateOf(false) }
    // the reference's note replay switch (VoiceModel sync flag): in memory only, on by default; replay itself is not built yet.
    var voiceReplayOn by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(recordingRows) { voiceList = VoiceRecordingList.update(voiceList, recordingRows) }
    val voiceMessages = mapOf(
        VoiceNotice.Saved to stringResource(R.string.feature_composer_voice_saved),
        VoiceNotice.SavedInterrupted to stringResource(R.string.feature_composer_voice_saved_call),
        VoiceNotice.SavedMaxLength to stringResource(R.string.feature_composer_voice_saved_limit),
        VoiceNotice.TooShort to stringResource(R.string.feature_composer_voice_too_short),
        VoiceNotice.Failed to stringResource(R.string.feature_composer_voice_failed),
    )
    val voiceBusy = stringResource(R.string.feature_composer_voice_busy)
    val voiceDenied = stringResource(R.string.feature_composer_voice_permission_denied)
    val voiceSettings = stringResource(R.string.feature_composer_voice_permission_settings)
    val voicePlayFailed = stringResource(R.string.feature_composer_voice_play_failed)
    LaunchedEffect(voiceNotices) { voiceNotices.collect { n -> voiceMessages[n]?.let { snackbar.showSnackbar(it) } } }
    LaunchedEffect(recordingHere != null, recordingHere?.paused) {
        // The timer; also the duration-cap check (the recorder's own callback is the main path).
        while (recordingHere != null) {
            recorder.tick()
            kotlinx.coroutines.delay(200)
        }
    }
    LaunchedEffect(player) {
        player.state.map { it.playing }.distinctUntilChanged().collectLatest { playing ->
            while (playing) { player.refresh(); kotlinx.coroutines.delay(200) }
        }
    }
    // The note's recordings are one playlist for the player; ids are stable across renames, a deleted playing one stops it.
    suspend fun registerTracks() {
        player.setTracks(recordingRows.map { Track(it.id, voiceFile(it.media), it.durationMs) })
    }
    LaunchedEffect(recordingRows) { registerTracks() }
    DisposableEffect(session) { onDispose { player.onEditorDisposed(context.isChangingConfigurations()) } }
    fun startRecording() {
        val pageIndex = currentPage
        val pageId = doc.pages.getOrNull(pageIndex)?.id
        val center = viewport.visibleDoc.center - latestLayout.pageOrigin(pageIndex)
        player.close()
        when (recorder.start(session.noteId, RecordingAnchor(pageId, center.x, center.y))) {
            StartOutcome.Started -> {
                recorderMinimized = false
                player.close(); voiceExtendOpen = false; voiceListOpen = false
                if (!VoiceRecordService.start(context)) {
                    recorder.cancel()
                    scope.launch { snackbar.showSnackbar(voiceMessages.getValue(VoiceNotice.Failed)) }
                }
            }
            StartOutcome.Busy -> scope.launch { snackbar.showSnackbar(voiceBusy) }
            StartOutcome.Failed -> scope.launch { snackbar.showSnackbar(voiceMessages.getValue(VoiceNotice.Failed)) }
        }
    }
    lateinit var micPermission: MicPermission
    micPermission = rememberMicPermission(
        onGranted = { startRecording() },
        onDenied = {
            scope.launch {
                val r = snackbar.showSnackbar(voiceDenied, actionLabel = voiceSettings)
                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) micPermission.openAppSettings()
            }
        },
    )
    micPermission.Dialogs()
    /** The voice object under a view point (page id and element), or null. */
    fun voiceAtView(p: Offset): Pair<String, ObjectElement>? {
        val d = viewport.viewToDoc(p)
        val l = latestLayout
        val index = l.pageAt(d) ?: return null
        val pageId = session.holder.current.pages.getOrNull(index)?.id ?: return null
        val el = session.objectAt(pageId, d - l.pageOrigin(index)) ?: return null
        return if (el.floating()?.obj is VoiceObject) pageId to el else null
    }
    fun playVoice(el: ObjectElement) {
        val v = el.floating()?.obj as? VoiceObject ?: return
        scope.launch {
            val file = voiceFile(v.media)
            if (file == null || !player.tap(v.id, file)) snackbar.showSnackbar(voicePlayFailed)
        }
    }

    // ---- object menu: Crop, Share, Save image, Play (item 5)
    /** The image being cropped: page, element, its frame when crop mode started, and the crop so far. */
    var cropPage by remember { mutableStateOf<Pair<String, ObjectElement>?>(null) }
    var cropFraction by remember { mutableStateOf(CropFraction()) }
    /** Lasso crop (images only): the outline drawn so far (image fractions) and whether the finger is still drawing it. */
    var cropLasso by remember { mutableStateOf(false) }
    var lassoOutline by remember { mutableStateOf<List<org.samesame.notes.core.model.document.Pt>>(emptyList()) }
    var lassoDrawing by remember { mutableStateOf(false) }
    val imageSaved = stringResource(R.string.feature_composer_obj_image_saved)
    val imageFailed = stringResource(R.string.feature_composer_obj_image_failed)
    fun startCrop(lasso: Boolean = false) {
        val s = session.selection.current
        val pageId = s.pageId ?: return
        val el = session.content(pageId)?.elements?.firstOrNull { it.id in s.ids } as? ObjectElement ?: return
        val obj = el.floating()?.obj
        val ok = obj is org.samesame.notes.core.model.document.ImageObject || (!lasso && obj is PaintingObject)
        if (!ok) return
        textState.hideKeyboard()
        session.clearSelection()
        cropFraction = CropFraction()
        cropLasso = lasso
        lassoOutline = emptyList()
        lassoDrawing = false
        cropPage = pageId to el
    }
    fun finishCrop(apply: Boolean) {
        val (pageId, el) = cropPage ?: return
        val lasso = cropLasso
        val outline = lassoOutline
        val lassoBox = if (lasso) LassoCrop.bounds(outline, LassoCrop.MIN_SIDE, LassoCrop.MIN_SIDE) else null
        val crop = if (lasso) lassoBox ?: CropFraction() else cropFraction
        cropPage = null
        cropLasso = false
        lassoOutline = emptyList()
        val media = when (val o = el.floating()?.obj) {
            is org.samesame.notes.core.model.document.ImageObject -> o.media
            is PaintingObject -> o.media
            else -> null
        }
        // A lasso around the whole picture still cuts away the corners, so only an empty crop is a no-op there.
        val noop = if (lasso) lassoBox == null else crop.isFull
        if (!apply || media == null || noop) {
            session.content(pageId)?.elements?.firstOrNull { it.id == el.id }?.let { session.select(pageId, listOf(it)) }
            return
        }
        scope.launch {
            val ext = media.substringAfterLast('.', "png")
            val out = session.loadMediaBytes(media)?.let { bytes ->
                withContext(Dispatchers.Default) {
                    if (lasso) ObjectFileActions.lassoCrop(bytes, crop, LassoCrop.inCrop(outline, crop))?.let { it to "png" }
                    else ObjectFileActions.crop(bytes, ext, crop)
                }
            }
            // A lasso spanning the whole picture still makes new pixels (transparent outside), so a full box is applied too.
            if (out == null || !session.cropImage(pageId, el.id, crop, out.first, out.second, allowFull = lasso)) snackbar.showSnackbar(saveFailed)
        }
    }
    // The body's floating selection menu with our extra rows (3c, 3f): view state = Copy, Select all, Share, Web search
    // (the reference's read-only menu); an editable selection of anchored objects only also offers Remove anchor.
    val bodyToolbar = remember(hostView) { ExtendedTextToolbar(hostView) }
    val shareLabel = stringResource(R.string.feature_composer_text_share)
    val webSearchLabel = stringResource(R.string.feature_composer_text_web_search)
    val removeAnchorLabel = stringResource(R.string.feature_composer_remove_anchor)
    val deleteLabel = stringResource(R.string.feature_composer_delete)
    val nothingToStraighten = stringResource(R.string.feature_composer_nothing_to_straighten)
    // Anchor to text / Remove anchor (3c): floating <-> inline through the body (one undo step each; see AnchorToText).
    fun anchorSelectionToText() {
        val (pageId, picked) = session.anchorableSelection() ?: return
        val pageIndex = doc.pages.indexOfFirst { it.id == pageId }
        if (pageIndex < 0 || pageIndex >= latestLayout.pageCount) return
        val origin = latestLayout.pageOrigin(pageIndex)
        session.clearSelection()
        session.group {
            // Lowest first: inserting an object only moves the text after it, so the higher ones still hit their spot.
            for ((id, f) in picked.sortedByDescending { it.second.rect.top }) {
                val left = origin.x + f.rect.left
                val top = origin.y + f.rect.top
                val end = textState.engine.caret(body.length)
                val feeds = AnchorToText.lineFeedsNeeded(end.bottom, top, end.bottom - end.top)
                if (feeds > 0) body.replace(body.length, body.length, "\n".repeat(feeds))
                val offset = textState.engine.hitTest(left, top + 1f)
                val obj = AnchorToText.inlineCopy(f)
                session.changeInlineObject(obj.id, obj)
                body.setSelection(offset)
                body.insertObject(obj.id)
                session.removeObjectElements(pageId, setOf(id))
            }
        }
        if (session.chrome.value.toolbar.mode == EditorMode.Text) textState.requestInput()
    }
    /** Delete the text selection (used for inline objects, whose one U+FFFC character removes the object). */
    fun deleteTextSelection() {
        val sel = body.selection
        if (sel.collapsed) return
        body.replace(sel.min, sel.max, "")
    }
    fun removeAnchorFromTextSelection() {
        val sel = body.selection
        if (sel.collapsed) return
        val objects = AnchorToText.objectOffsets(body.slice(sel.min, sel.max), sel.min)
        if (objects.isEmpty()) return
        session.group {
            for ((offset, id) in objects.sortedByDescending { it.first }) {
                val obj = session.holder.inlineObject(id) ?: continue
                val caret = textState.engine.caret(offset)
                val pageIndex = caret.pageIndex.coerceIn(0, doc.pages.size - 1)
                val pageId = doc.pages.getOrNull(pageIndex)?.id ?: continue
                val origin = latestLayout.pageOrigin(pageIndex)
                val b = textState.engine.selectionBounds(offset, offset + 1)
                val floating = AnchorToText.floatingFor(obj, b.left - origin.x, b.top - origin.y, b.right - b.left)
                body.setSelection(offset, offset + 1)
                body.replace(offset, offset + 1, "")
                session.changeInlineObject(id, null)
                session.addFloatingObject(pageId, floating)
            }
        }
    }
    SideEffect {
        bodyToolbar.extras = extras@{
            val sel = body.selection
            if (sel.collapsed) return@extras emptyList()
            val t = session.chrome.value.toolbar
            val slice = body.slice(sel.min, sel.max)
            when (AnchorToText.selectionMenu(t.mode == EditorMode.Reading && t.viewMode, t.mode == EditorMode.Text, slice)) {
                AnchorToText.SelectionMenu.ReadOnly -> {
                    val text = slice.plainText()
                    listOf(
                        ExtendedTextToolbar.Extra(shareLabel) { TextActions.share(context, text) },
                        ExtendedTextToolbar.Extra(webSearchLabel) { TextActions.webSearch(context, text) },
                    )
                }
                // A selection that holds inline (anchored) objects, alone or among text: the reference's object menu for
                // an inline object is Remove anchor (+ Delete / Cut / Copy).
                AnchorToText.SelectionMenu.InlineObjects ->
                    listOf(
                        ExtendedTextToolbar.Extra(removeAnchorLabel) { removeAnchorFromTextSelection() },
                        ExtendedTextToolbar.Extra(deleteLabel) { deleteTextSelection() },
                    )
                AnchorToText.SelectionMenu.None -> emptyList()
            }
        }
    }
    /** Media files of the selected objects for sharing / saving: (bundle path, extension) of images, drawings and voice. */
    fun selectedMedia(): List<org.samesame.notes.core.model.document.FloatingObject> = session.selectedObjects()
    fun shareSelection() {
        val objects = selectedMedia()
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "share/objects").apply { deleteRecursively(); mkdirs() }
                objects.mapIndexedNotNull { i, f ->
                    val media = when (val o = f.obj) {
                        is org.samesame.notes.core.model.document.ImageObject -> o.media
                        is PaintingObject -> o.media
                        is VoiceObject -> o.media
                        else -> null
                    } ?: return@mapIndexedNotNull null
                    val ext = media.substringAfterLast('.', "bin")
                    val bytes = session.loadMediaBytes(media) ?: return@mapIndexedNotNull null
                    val out = File(dir, "%s %d.%s".format(if (f.obj is VoiceObject) "Voice" else "Image", i + 1, ext))
                    runCatching { out.writeBytes(bytes) }.getOrNull()?.let { out to ObjectFileActions.mimeFor(ext) }
                }
            }
            val text = objects.mapNotNull { (it.obj as? TextBoxObject)?.text?.text?.takeIf { t -> t.isNotBlank() } }.joinToString("\n\n")
            runCatching {
                when {
                    files.isNotEmpty() -> context.startActivity(ShareFiles.chooser(context, files, null))
                    text.isNotEmpty() -> context.startActivity(
                        android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, text),
                            null,
                        ),
                    )
                    else -> snackbar.showSnackbar(saveFailed)
                }
            }
        }
    }
    // Edit image (object menu): the picture goes to any installed editor (ACTION_EDIT, read + write grant on a cache copy).
    // On return the editor's output (a returned uri, or our copy written back) replaces the image; nothing changed = nothing.
    val noImageEditor = stringResource(R.string.feature_composer_no_image_editor)
    val imageEditFailed = stringResource(R.string.feature_composer_image_edit_failed)
    var pendingEdit by remember { mutableStateOf<PendingImageEdit?>(null) }
    val editImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val p = pendingEdit ?: return@rememberLauncherForActivityResult
        pendingEdit = null
        scope.launch {
            val ourUri = ShareFiles.uriFor(context, p.file)
            val edited = withContext(Dispatchers.IO) {
                val returned = result.data?.data?.takeIf { it != ourUri }
                val bytes = returned?.let { u ->
                    runCatching { context.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull()
                } ?: p.file.takeIf { it.length() > 0 }?.readBytes()
                p.file.delete()
                bytes?.takeIf { !it.contentEquals(p.original) }?.let { b ->
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(b, 0, b.size, o)
                    if (o.outWidth <= 0 || o.outHeight <= 0) null
                    else Triple(b, ObjectFileActions.extensionFor(o.outMimeType), o.outWidth to o.outHeight)
                }
            } ?: return@launch
            val (bytes, ext, size) = edited
            if (!session.replaceImage(p.pageId, p.id, bytes, ext, size.first, size.second)) snackbar.showSnackbar(imageEditFailed)
        }
    }
    fun editSelectedImage() {
        val (pageId, f) = session.selectedPicture() ?: return
        val img = f.obj as? org.samesame.notes.core.model.document.ImageObject ?: return
        val id = session.selection.current.ids.singleOrNull() ?: return
        scope.launch {
            val bytes = session.loadMediaBytes(img.media)
            if (bytes == null) { snackbar.showSnackbar(imageEditFailed); return@launch }
            val ext = img.media.substringAfterLast('.', "png")
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "share/edit").apply { deleteRecursively(); mkdirs() }
                File(dir, "Image.$ext").also { it.writeBytes(bytes) }
            }
            val uri = ShareFiles.uriFor(context, file)
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val edit = android.content.Intent(android.content.Intent.ACTION_EDIT)
                .setDataAndType(uri, ObjectFileActions.mimeFor(ext))
                .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(flags)
            if (context.packageManager.queryIntentActivities(edit, 0).isEmpty()) {
                file.delete()
                snackbar.showSnackbar(noImageEditor)
                return@launch
            }
            pendingEdit = PendingImageEdit(pageId, id, file, bytes)
            try {
                editImageLauncher.launch(android.content.Intent.createChooser(edit, null).addFlags(flags))
            } catch (_: ActivityNotFoundException) {
                pendingEdit = null
                file.delete()
                snackbar.showSnackbar(noImageEditor)
            }
        }
    }
    // Before API 29 there is no permission-free gallery write: the picture goes through the system file picker instead.
    var pendingImageSave by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    val saveImageDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
        val pending = pendingImageSave
        pendingImageSave = null
        if (uri != null && pending != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(pending.first) } != null }.getOrDefault(false)
            }
            snackbar.showSnackbar(if (ok) imageSaved else imageFailed)
        }
    }
    fun saveSelectedImages() {
        val media = selectedMedia().mapNotNull { f ->
            when (val o = f.obj) {
                is org.samesame.notes.core.model.document.ImageObject -> o.media
                is PaintingObject -> o.media
                else -> null
            }
        }
        if (media.isEmpty()) return
        scope.launch {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(java.util.Date())
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                val path = media.first()
                val bytes = session.loadMediaBytes(path)
                if (bytes == null) {
                    snackbar.showSnackbar(imageFailed)
                    return@launch
                }
                val ext = path.substringAfterLast('.', "png")
                pendingImageSave = bytes to ext
                saveImageDocument.launch("Image_$stamp.$ext")
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                media.withIndex().all { (i, path) ->
                    val bytes = session.loadMediaBytes(path) ?: return@all false
                    val name = if (media.size == 1) "Image_$stamp" else "Image_${stamp}_${i + 1}"
                    ObjectFileActions.saveToGallery(context, bytes, path.substringAfterLast('.', "png"), name)
                }
            }
            snackbar.showSnackbar(if (ok) imageSaved else imageFailed)
        }
    }
    fun playSelectedVoice() {
        val s = session.selection.current
        val pageId = s.pageId ?: return
        (session.content(pageId)?.elements?.firstOrNull { it.id in s.ids } as? ObjectElement)?.let { playVoice(it) }
    }
    BackHandler(enabled = cropPage != null) { finishCrop(apply = false) }
    /** List row tap: plays that recording from its start. */
    fun playRecording(r: VoiceRecording) {
        scope.launch {
            registerTracks()
            if (!player.playTrack(r.id)) snackbar.showSnackbar(voicePlayFailed)
        }
    }
    /** Play/pause of the playlist (menu and pill); idle it plays from the seek bar position. */
    fun togglePlaylist() {
        scope.launch {
            registerTracks()
            if (!player.togglePlay()) snackbar.showSnackbar(voicePlayFailed)
        }
    }
    /** Shares the selected recordings as audio files through the share sheet (the reference's list Share). */
    fun shareRecordings(items: List<VoiceRecording>) {
        // Locked notes are never exported, their recordings included (security review L2).
        if (locked) { lockedSaveNotice = true; return }
        scope.launch {
            // The bundle's media files live outside the FileProvider's `cache/share`, so each is copied there as `<name>.m4a`.
            val dir = File(context.cacheDir, "share/voice").apply { deleteRecursively(); mkdirs() }
            val files = withContext(Dispatchers.IO) {
                items.mapIndexedNotNull { i, r ->
                    val src = voiceFile(r.media) ?: return@mapIndexedNotNull null
                    val base = r.name.ifBlank { "Voice %03d".format(i + 1) }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val ext = src.extension.ifBlank { "m4a" }
                    val out = File(dir, "$base.$ext").let { if (it.exists()) File(dir, "$base ${i + 1}.$ext") else it }
                    runCatching { src.copyTo(out, overwrite = true) }.getOrNull()?.let { it to "audio/mp4" }
                }
            }
            if (files.isEmpty()) snackbar.showSnackbar(voicePlayFailed)
            else runCatching { context.startActivity(ShareFiles.chooser(context, files, null)) }
        }
    }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(
            // Under a lock page (opaque) the editor keeps its size while that page's keyboard moves: re-measuring the
            // whole editor every frame of the keyboard animation made the page stutter on big notes. The lock page
            // pads for the keyboard itself.
            if (lockFlow || unlockFlow) WindowInsets.safeDrawing.exclude(WindowInsets.ime) else WindowInsets.safeDrawing,
        ),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!tb.fullScreen && searchOpen) {
                NoteSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    canForward = searchNav.canForward.also { searchTick },
                    canBackward = searchNav.canBackward.also { searchTick },
                    onForward = { showHit(searchNav.forward()) },
                    onBackward = { showHit(searchNav.backward()) },
                    onSubmit = { if (searchPending) runSearch() else showHit(searchNav.forward()) },
                    onClose = { searchOpen = false; searchQuery = "" },
                )
            } else if (!tb.fullScreen) {
                ComposerTopBar(
                    title = title,
                    reading = tb.mode == EditorMode.Reading,
                    onTitleChange = { title = it; session.setTitle(it) },
                    onTitleDone = { textState.requestInput() },
                    onBack = { leaveOrAsk(BackPolicy.toolbarBack(settings.autoSaveNotes, session.hasUnsavedChanges())) },
                    onReading = { session.dispatch(ToolbarIntent.EnterReading) },
                    onEdit = { session.dispatch(ToolbarIntent.ExitReading) },
                    onInsert = { session.dispatch(ToolbarIntent.OpenMenu(Menu.Insert)) },
                    onSearch = { searchOpen = true },
                    onMore = { session.dispatch(ToolbarIntent.OpenMenu(Menu.Overflow)) },
                    meta = noteMeta,
                    onToggleFavorite = onToggleFavorite,
                    viewState = tb.viewMode,
                    // The expanded title header hangs over the page below the bar, so the bar draws (and takes touches) on top.
                    modifier = Modifier.zIndex(1f),
                )
            }
            if (!tb.fullScreen && recordingHere == null && hasRecordings && (voiceExtendOpen || playOpen)) {
                VoiceDockedMenu(
                    player = player,
                    idleTotalMs = totalRecordMs,
                    listOpen = voiceListOpen,
                    canRecord = tb.mode != EditorMode.Reading,
                    onCollapse = { player.close(); voiceExtendOpen = false; voiceListOpen = false },
                    onTogglePlay = ::togglePlaylist,
                    onRecord = { micPermission.begin() },
                    onToggleList = { voiceListOpen = !voiceListOpen },
                ) {
                    VoiceRecordingListPanel(
                        state = voiceList,
                        player = player,
                        replayOn = voiceReplayOn,
                        onReplayChange = { voiceReplayOn = it },
                        onRowTap = { r ->
                            if (voiceList.selectMode) voiceList = VoiceRecordingList.toggle(voiceList, r.id)
                            else playRecording(r)
                        },
                        onRowLongPress = { r -> voiceList = VoiceRecordingList.longPress(voiceList, r.id) },
                        onEdit = { voiceList = VoiceRecordingList.enterSelectMode(voiceList) },
                        onSelectAll = { voiceList = VoiceRecordingList.selectAll(voiceList) },
                        onRename = { r, name -> session.renameVoiceRecording(r.id, name) },
                        onShare = ::shareRecordings,
                        onDelete = { items ->
                            if (player.state.value.objectId in items.map { it.id }) player.close()
                            session.deleteVoiceRecordings(items.map { it.id })
                            voiceList = VoiceRecordingList.exitSelectMode(voiceList)
                        },
                        onExitSelectMode = { voiceList = VoiceRecordingList.exitSelectMode(voiceList) },
                    )
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().onGloballyPositioned {
                    val b = it.boundsInParent()
                    if (b != canvasBounds.value) canvasBounds.value = b
                },
            ) {
                NoteCanvas(
                    source = source,
                    viewport = viewport,
                    brush = brush,
                    inputSettings = inputSettings,
                    modifier = Modifier.fillMaxSize(),
                    darkTheme = dark,
                    toolHandler = handlers::forTool,
                    onStylusSeen = { if (!prefs.stylusSeen) session.toolController.updatePrefs { it.withStylusSeen() } },
                    startsTextInteraction = { p -> textState.handleAt(p) != null },
                    pullToAddPage = {
                        val t = session.chrome.value.toolbar
                        val allowed = session.holder.current.layout.mode != PageMode.Infinite && t.mode != EditorMode.Reading &&
                            latestEditor == null && cropPage == null
                        if (allowed) pullToAdd to !latestLayout.horizontal else null
                    },
                    // The router asks this for a first touch only in text mode, and for both fingers of a pinch in text and
                    // pen mode (both on the selected object = scale / rotate it instead of zooming the page).
                    startsObjectGesture = { p ->
                        session.chrome.value.toolbar.mode != EditorMode.Reading && latestEditor == null && cropPage == null &&
                            grabsSelectedObject(p)
                    },
                    objectImage = images::image,
                    backgroundImage = backgrounds::image,
                    objectKey = { images.version },
                    hiddenElement = { editor?.id },
                    liveSelection = {
                        val s = selectionState.value
                        val m = s.liveTransform
                        if (m != null && s.pageId != null) LiveSelection(s.pageId, s.ids, m) else null
                    },
                    straightLine = penStyle.straightLine,
                    // Body text: static glyphs cached per page layer, selection / search / caret live (perf ux5b).
                    pageText = textState.pagePainter,
                    overlay = {
                        ToolOverlayLayer(handlers, selectionState, doc.pages.map { it.id }, layout, viewport, dark)
                        if (tb.mode == EditorMode.Reading && tb.viewMode) {
                            // View state (an opened note): a voice object plays; long press selects a word with handles and
                            // Copy / Select all but stays in view; every other tap goes to `viewTap` (link, checkbox, or
                            // touch to edit). The voice taps share the node: sibling overlays don't share touches.
                            CompositionLocalProvider(LocalTextToolbar provides bodyToolbar) { RichTextToolbarEffect(textState) { textCoords } }
                            Box(
                                Modifier.fillMaxSize()
                                    .onGloballyPositioned { textCoords = it }
                                    .pointerInput(Unit) {
                                        // Watches (never consumes) which kind of pointer pressed, for `viewTap`.
                                        awaitPointerEventScope {
                                            while (true) {
                                                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                                e.changes.firstOrNull { it.pressed && !it.previousPressed }?.let {
                                                    lastViewDownStylus = it.type == androidx.compose.ui.input.pointer.PointerType.Stylus ||
                                                        it.type == androidx.compose.ui.input.pointer.PointerType.Eraser
                                                }
                                            }
                                        }
                                    }
                                    .textBoxTaps(
                                        hit = { p -> voiceAtView(p) != null },
                                        onTap = { p -> voiceAtView(p)?.let { playVoice(it.second) } },
                                        onLongPress = {},
                                    )
                                    .richTextGestures(textState)
                                    .richTextSelectionDecorations(textState),
                            )
                        } else if (tb.mode == EditorMode.Reading) {
                            // Book-icon reading mode (read only): a tap plays a voice object or follows a link, nothing else.
                            Box(
                                Modifier.fillMaxSize().textBoxTaps(
                                    hit = { true },
                                    onTap = { p ->
                                        val voice = voiceAtView(p)
                                        if (voice != null) playVoice(voice.second) else textState.onReadTap(p)
                                    },
                                    onLongPress = {},
                                ),
                            )
                        }
                        val boxEditor = editor
                        if (boxEditor != null) {
                            val index = doc.pages.indexOfFirst { it.id == boxEditor.pageId }
                            if (index >= 0 && index < layout.pageCount) TextBoxEditOverlay(
                                editor = boxEditor,
                                viewport = viewport,
                                pageOrigin = layout.pageOrigin(index),
                                dark = dark,
                                onOutsideTap = { p ->
                                    // A tap on another text box moves the edit there; anywhere else it just ends it.
                                    val other = textBoxAtView(p)?.takeIf { it.second.id != boxEditor.id }
                                    endTextBoxEdit()
                                    if (other != null) beginTextBoxEdit(other.first, other.second.id, fresh = false)
                                },
                            )
                        } else if (textMode) {
                            RichTextCaretBlink(textState)
                            CompositionLocalProvider(LocalTextToolbar provides bodyToolbar) { RichTextToolbarEffect(textState) { textCoords } }
                            Box(
                                Modifier.fillMaxSize()
                                    .onGloballyPositioned { textCoords = it }
                                    // A tap away from every object drops an object selection (the tap still places the caret).
                                    .plainTaps { p ->
                                        if (session.selection.current.ids.isNotEmpty() &&
                                            textBoxAtView(p) == null && imageAtView(p) == null && voiceAtView(p) == null
                                        ) session.clearSelection()
                                    }
                                    .textBoxTaps(
                                        hit = { p ->
                                            textBoxAtView(p) != null || imageAtView(p) != null || voiceAtView(p) != null ||
                                                inlineObjectAtView(p) != null
                                        },
                                        onTap = { p ->
                                            // An inline (anchored) object is selected like text: the toolbar then offers
                                            // Remove anchor and Delete for it.
                                            inlineObjectAtView(p)?.let { o ->
                                                session.clearSelection()
                                                textState.selectRange(o, o + 1)
                                                return@textBoxTaps
                                            }
                                            // A tap on a voice object plays it (in pen mode a tap draws). One node with the text
                                            // gestures: sibling overlays don't share touches.
                                            val voice = voiceAtView(p)
                                            if (voice != null) {
                                                playVoice(voice.second)
                                                return@textBoxTaps
                                            }
                                            // A tap on a placed image selects it with the transform chrome, staying in text mode.
                                            if (textBoxAtView(p) == null) imageAtView(p)?.let { (pageId, el) -> selectTextBoxWithChrome(pageId, el) }
                                            textBoxAtView(p)?.let { (pageId, el) ->
                                                val sel = session.selection.current
                                                when (textBoxTapAction(sel.pageId, sel.ids, pageId, el.id)) {
                                                    // First tap only selects (with the lasso chrome); the next one edits.
                                                    TextBoxTapAction.Select -> selectTextBoxWithChrome(pageId, el)
                                                    TextBoxTapAction.Edit -> beginTextBoxEdit(pageId, el.id, fresh = false)
                                                }
                                            }
                                        },
                                        // Long press: select the box with its handles but stay in text mode (the reference
                                        // `onItemLongClicked` only calls selectObject; no brush/mode change).
                                        onLongPress = { p ->
                                            val inline = inlineObjectAtView(p)
                                            if (inline != null) {
                                                session.clearSelection()
                                                textState.selectRange(inline, inline + 1)
                                            } else {
                                                (textBoxAtView(p) ?: imageAtView(p))?.let { (pageId, el) -> selectTextBoxWithChrome(pageId, el) }
                                            }
                                        },
                                    )
                                    .richTextInput(textState)
                                    .richTextGestures(textState)
                                    .richTextSelectionDecorations(textState),
                            )
                        }
                    },
                )
                if (!infinite) PullToAddPageIndicator(
                    state = pullToAdd,
                    horizontal = layout.horizontal,
                    // The colour of the page that will be added (the last page's follower: same colour/template), so
                    // the sheet slid over the viewport turns into the real page without a colour flash at the end.
                    pageColor = org.samesame.notes.feature.composer.canvas.pageColor(
                        doc.pages.lastOrNull()?.follower("") ?: PageSpec(id = ""),
                        doc.style.backgroundColor, dark, darkAdjust,
                    ),
                    inkColor = if (darkAdjust) Color(0xFFBDBDBD) else Color(0xFF6E6E6E),
                )
                // Crop mode (object menu "Crop"): modal over the canvas until Cancel / Done / Back.
                cropPage?.let { (pageId, el) ->
                    val index = doc.pages.indexOfFirst { it.id == pageId }
                    val f = el.floating()
                    if (index >= 0 && index < layout.pageCount && f != null && cropLasso) LassoCropOverlay(
                        frame = f.frame,
                        pageOrigin = layout.pageOrigin(index),
                        viewport = viewport,
                        outline = lassoOutline,
                        drawing = lassoDrawing,
                        canFinish = LassoCrop.bounds(lassoOutline, LassoCrop.MIN_SIDE, LassoCrop.MIN_SIDE) != null,
                        onOutlineChange = { pts, drawingNow -> lassoOutline = pts; lassoDrawing = drawingNow },
                        onCancel = { finishCrop(apply = false) },
                        onDone = { finishCrop(apply = true) },
                    ) else if (index >= 0 && index < layout.pageCount && f != null) ImageCropOverlay(
                        frame = f.frame,
                        pageOrigin = layout.pageOrigin(index),
                        viewport = viewport,
                        crop = cropFraction,
                        onCropChange = { cropFraction = it },
                        onCancel = { finishCrop(apply = false) },
                        onDone = { finishCrop(apply = true) },
                    )
                }
                // The editor's fast scroll bar (vertical, or along the bottom in horizontal page view). The reference hides
                // it while editing when "Hide scroll bar when editing" is on; reading mode still shows it. Viewport state is
                // read inside lambdas (draw / layout / effects), so scrolling never recomposes this screen.
                val horizontalBar = layout.horizontal
                val barNeeded by remember(viewport, horizontalBar) {
                    derivedStateOf {
                        val s = viewport.scale
                        if (s <= 0f) false
                        else if (horizontalBar) viewport.content.width * s > viewport.size.width + 1f
                        else viewport.content.height * s > viewport.size.height + 1f
                    }
                }
                if (barNeeded && !(settings.hideScrollBar && tb.mode != EditorMode.Reading)) {
                    val pagesMode = doc.layout.mode != PageMode.Infinite
                    EditorScrollBar(
                        scrollFraction = {
                            val s = viewport.scale
                            if (horizontalBar) ScrollBarGeometry.scrollFraction(viewport.scroll.x, viewport.content.width, viewport.size.width / s)
                            else ScrollBarGeometry.scrollFraction(viewport.scroll.y, viewport.content.height, viewport.size.height / s)
                        },
                        horizontal = horizontalBar,
                        onScrub = { f ->
                            val s = viewport.scale
                            if (horizontalBar) {
                                val maxDoc = (viewport.content.width - viewport.size.width / s).coerceAtLeast(0f)
                                viewport.scrollTo(Offset(f * maxDoc, viewport.scroll.y))
                            } else {
                                val maxDoc = (viewport.content.height - viewport.size.height / s).coerceAtLeast(0f)
                                viewport.scrollTo(Offset(viewport.scroll.x, f * maxDoc))
                            }
                        },
                        pageLabel = if (pagesMode) ({ "%d/%d".format(currentPage + 1, doc.pages.size) }) else null,
                    )
                }
                if (bodyGuideVisible && !tb.fullScreen) {
                    androidx.compose.material3.Text(
                        stringResource(R.string.feature_composer_body_hint),
                        color = Color(0xFFA0A0A0), fontSize = org.samesame.notes.core.designsystem.theme.fixedSp(16f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp), // composer_text_box_margin
                    )
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                if (!tb.fullScreen && (recordingHere != null || (hasRecordings && !voiceExtendOpen && !playOpen))) {
                    // The floating pill: recording, or the collapsed state of a note that already has recordings.
                    VoiceRecordPill(
                        recording = recordingHere,
                        totalMs = totalRecordMs,
                        editable = tb.mode != EditorMode.Reading,
                        minimized = if (recordingHere != null) recorderMinimized else voiceBarMinimized,
                        onMinimizeChange = { if (recordingHere != null) recorderMinimized = it else voiceBarMinimized = it },
                        onStop = { recorder.stop() },
                        onRecord = { micPermission.begin() },
                        onPlay = { voiceExtendOpen = true; togglePlaylist() },
                        onExpand = { voiceExtendOpen = true },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = tb.fullScreen,
                    modifier = Modifier.align(Alignment.TopEnd),
                    enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(333)),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(333)),
                ) {
                    // #61: 30 dp disc (fill almost the surround colour, #222 rim) centred 45.7 dp from the right, 30 dp below the canvas top.
                    Box(
                        Modifier.padding(top = 15.dp, end = 30.7.dp).size(30.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(androidx.compose.ui.graphics.Color(0x14000000))
                            .clickable(onClickLabel = null, role = androidx.compose.ui.semantics.Role.Button) { session.dispatch(ToolbarIntent.SetFullScreen(false)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            AppIcons.FullscreenExit,
                            stringResource(R.string.feature_composer_exit_full_screen),
                            Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
            if (easyPadOpen && tb.mode == EditorMode.Pen && !tb.fullScreen) {
                val padBrush = remember(prefs.penType, prefs.penLevel, prefs.penColor, prefs.penPressureSensitive) {
                    PenCatalog.specFor(prefs.penType).brush(prefs.penLevel, prefs.penColor, prefs.penPressureSensitive)
                }
                EasyWritingPad(
                    slot = padSlot,
                    slotKey = padKey,
                    pageIdOf = { i -> doc.pages.getOrNull(i)?.id },
                    brush = padBrush,
                    guideVisible = !prefs.easyPadGuideSeen,
                    onGuideSeen = { if (!prefs.easyPadGuideSeen) session.toolController.updatePrefs { it.copy(easyPadGuideSeen = true) } },
                    onAdd = { pageId, el -> session.addElements(pageId, listOf(el)) },
                    onRemove = { pageId, ids -> session.editElements(pageId) { list -> list.filter { it.id !in ids } } },
                    onEnter = { session.breakTypingBurst(); body.setSelection(EasyPadMath.enterOffset(body.editable, body.selection.min)); body.insertNewline() },
                    onClose = { easyPadOpen = false },
                )
            }
            if (!tb.fullScreen) AnimatedContent(
                targetState = tb.mode,
                transitionSpec = {
                    // Text <-> pen cross-fades in 150 ms; entering or leaving reading mode doesn't animate.
                    if (initialState == EditorMode.Reading || targetState == EditorMode.Reading) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        fadeIn(tween(SameSameMotion.ToolbarSwapMs, easing = SameSameMotion.SineInOut33)) togetherWith
                            fadeOut(tween(SameSameMotion.ToolbarSwapMs, easing = SameSameMotion.SineInOut33)) using
                            SizeTransform(clip = false) { _, _ -> snap() }
                    }
                },
                label = "toolbarMode",
            ) { mode -> when (mode) {
                EditorMode.Text -> {
                    val cs = activeBody.currentCharStyle()
                    TextToolbar(
                        model = TextToolbarModel(
                            fontSize = TextSizeCatalog.selectedSize(cs.fontSize),
                            fontSizeLabel = fontSizeButtonLabel(
                                TextSizeCatalog.selectedSize(cs.fontSize),
                                TextSizeCatalog.headingFor(cs.fontSize, cs.bold),
                            ),
                            textColor = cs.color?.toColor() ?: androidx.compose.ui.graphics.Color.Unspecified,
                            highlightColor = cs.highlight?.toColor() ?: androidx.compose.ui.graphics.Color.Unspecified,
                            canUndo = editor?.canUndo ?: chrome.undo.canUndo,
                            canRedo = editor?.canRedo ?: chrome.undo.canRedo,
                            openPanel = when (tb.panel) {
                                Panel.TextOptions -> TextToolbarButton.TextOptions
                                Panel.FontColor -> TextToolbarButton.FontColor
                                Panel.Highlight -> TextToolbarButton.Highlight
                                Panel.FontSize -> TextToolbarButton.FontSize
                                else -> null
                            },
                        ),
                        onHandwriting = { session.dispatch(ToolbarIntent.ToggleHandwriting) },
                        onTodo = { editor?.breakTypingBurst() ?: session.breakTypingBurst(); activeBody.toggleList(ListKind.Checklist) },
                        onTextOptions = { session.dispatch(ToolbarIntent.TogglePanel(Panel.TextOptions)) },
                        onFontColor = { session.dispatch(ToolbarIntent.TogglePanel(Panel.FontColor)) },
                        onHighlight = { session.dispatch(ToolbarIntent.TogglePanel(Panel.Highlight)) },
                        onFontSize = { session.dispatch(ToolbarIntent.TogglePanel(Panel.FontSize)) },
                        onUndo = ::undo,
                        onRedo = ::redo,
                        fontSizeHold = fontSizeHold,
                        onFontSizeHoldOpen = {
                            if (tb.panel != Panel.FontSize) session.dispatch(ToolbarIntent.TogglePanel(Panel.FontSize))
                        },
                        onFontSizePick = ::applyFontSizePick,
                        onFontSizeCancel = { session.dispatch(ToolbarIntent.ClosePanel) },
                    )
                }
                EditorMode.Pen -> PenModeToolbar(
                    model = PenToolbarModel(
                        tool = tb.tool,
                        penColor = prefs.penColor.toColor(),
                        highlighterColor = prefs.highlighterColor.toColor(),
                        highlighterOpacity = prefs.highlighterOpacity,
                        selectionShape = tools.selectionShape,
                        canUndo = chrome.undo.canUndo,
                        canRedo = chrome.undo.canRedo,
                    ),
                    onKeyboard = { session.dispatch(ToolbarIntent.ToggleHandwriting) },
                    onTool = { session.dispatch(ToolbarIntent.TapTool(it)) },
                    onUndo = ::undo,
                    onRedo = ::redo,
                    onStyle = { /* handwriting style: the reference (#77) opened no panel */ },
                    onEasyPad = { easyPadOpen = !easyPadOpen; if (easyPadOpen) session.dispatch(ToolbarIntent.ClosePanel) },
                    showStyle = settings.toolbarChangeStyle,
                    showEasyPad = settings.toolbarEasyWritingPad,
                )
                EditorMode.Reading -> Unit
            } }
        }
        if (tb.mode != EditorMode.Reading && !tb.fullScreen) {
            ComposerPanelLayer(
                session = session,
                body = activeBody,
                activeState = activeState,
                tb = tb,
                tools = tools,
                eraseAllOpen = eraseAllOpen,
                onEraseAll = { eraseAllOpen = !eraseAllOpen },
                onEraseAllPage = ::eraseAll,
                currentPage = currentPage,
                pageCount = doc.pages.size,
                fontSizeHold = fontSizeHold,
                onFontSizePick = ::applyFontSizePick,
                modifier = Modifier.padding(bottom = ToolbarHeight),
            )
        }

        // ---- WP-J2 menus (over a transparent scrim) -------------------------------------------
        if (tb.menu != null) {
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { closeMenu() },
            )
        }
        // #53, #54: popups start 6.1 dp below the status bar; right margins 9.9 dp (insert) and 7.2 dp (overflow).
        val menuAnchor = Modifier.align(Alignment.TopEnd).padding(end = 7.2.dp)
            .padding(top = 6.1.dp)
        val insertAnchor = Modifier.align(Alignment.TopEnd).padding(end = 9.9.dp).padding(top = 6.1.dp)
        // The menu that was open last stays composed while its exit animation plays.
        val heldMenu = remember { arrayOfNulls<Menu>(1) }
        if (tb.menu != null) heldMenu[0] = tb.menu
        when (tb.menu ?: heldMenu[0]) {
            Menu.Insert -> MotionPopup(tb.menu == Menu.Insert, PopupMotion.EditorMenu, insertAnchor) {
                InsertMenu(
                    onImage = {
                        closeMenu()
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onCamera = {
                        closeMenu()
                        openCamera()
                    },
                    onPdf = {
                        closeMenu()
                        pdfPicker.launch(arrayOf("application/pdf"))
                    },
                    onTextBox = {
                        closeMenu()
                        insertTextBox()
                    },
                    onVoice = {
                        closeMenu()
                        micPermission.begin()
                    },
                    onDrawing = if (newDrawingSession == null) null else {
                        {
                            closeMenu()
                            openDrawingSheet()
                        }
                    },
                )
            }
            Menu.Overflow -> MotionPopup(tb.menu == Menu.Overflow, PopupMotion.EditorMenu, menuAnchor) { if (tb.mode == EditorMode.Reading) {
                ReadingOverflowMenu(
                    locked = locked,
                    favorite = favorite,
                    onAction = { action ->
                        closeMenu()
                        when (action) {
                            ReadingAction.PageSorter -> pageScreen = ComposerPage.Sorter
                            ReadingAction.PageView -> session.setLayout(
                                doc.layout.copy(
                                    scroll = if (doc.layout.scroll == ScrollDirection.Vertical) ScrollDirection.Horizontal
                                    else ScrollDirection.Vertical,
                                ),
                            )
                            ReadingAction.FullScreen -> session.dispatch(ToolbarIntent.SetFullScreen(true))
                            ReadingAction.Tags -> tagsOpen = true
                            ReadingAction.SaveAsFile -> if (locked) lockedSaveNotice = true else saveAsOpen = true
                            ReadingAction.Share -> if (locked) lockedSaveNotice = true else shareMenuOpen = true
                            ReadingAction.Lock -> if (locked) unlockConfirm = true else lockFlow = true
                            ReadingAction.Favourite -> onToggleFavorite()
                            ReadingAction.Delete -> trashConfirm = true
                            ReadingAction.Print -> printNote()
                        }
                    },
                )
            } else {
                OverflowMenu(
                    penMode = tb.mode == EditorMode.Pen,
                    favorite = favorite,
                    onAction = { action ->
                        closeMenu()
                        when (action) {
                            OverflowAction.PageSorter -> pageScreen = ComposerPage.Sorter
                            OverflowAction.PageTemplate -> pageScreen = ComposerPage.Template
                            OverflowAction.PageView -> session.setLayout(
                                doc.layout.copy(
                                    scroll = if (doc.layout.scroll == ScrollDirection.Vertical) ScrollDirection.Horizontal
                                    else ScrollDirection.Vertical,
                                ),
                            )
                            OverflowAction.PageSettings -> session.dispatch(ToolbarIntent.OpenMenu(Menu.PageSettings))
                            OverflowAction.FullScreen -> session.dispatch(ToolbarIntent.SetFullScreen(true))
                            OverflowAction.Tags -> tagsOpen = true
                            OverflowAction.SaveAsFile -> if (locked) lockedSaveNotice = true else saveAsOpen = true
                            OverflowAction.Share -> if (locked) lockedSaveNotice = true else shareMenuOpen = true
                            OverflowAction.Favourite -> onToggleFavorite()
                            OverflowAction.Delete -> trashConfirm = true
                            OverflowAction.Print -> printNote()
                        }
                    },
                )
            } }
            Menu.PageSettings -> MotionPopup(tb.menu == Menu.PageSettings, PopupMotion.EditorMenu, insertAnchor) {
                PageSettingsPopup(
                    layout = doc.layout,
                    style = doc.style,
                    onScroll = { session.setLayout(doc.layout.copy(scroll = it)) },
                    onBackground = { session.setStyle(doc.style.copy(backgroundColor = it)) },
                    onAdjustDark = { session.setStyle(doc.style.copy(adjustForDarkMode = it)) },
                )
            }
            Menu.SelectionMore, null -> Unit
        }

        // ---- WP-J2 selection pill / more menu (not while an image is cropped) ----------------
        if (cropPage == null) SelectionActionOverlay(
            session = session,
            pages = doc.pages,
            layout = layout,
            viewport = viewport,
            tb = tb,
            settings = settings,
            currentPage = currentPage,
            canvasBounds = canvasBounds,
            onCloseMenu = ::closeMenu,
            onEditDrawing = {
                val s = session.selectionState.value
                val id = s.ids.singleOrNull()
                if (s.pageId != null && id != null) editSelectedDrawing(s.pageId, id)
            },
            fileActions = SelectionFileActions(
                onCrop = { startCrop() },
                onLassoCrop = { startCrop(lasso = true) },
                onEditImage = ::editSelectedImage,
                onAnchor = ::anchorSelectionToText,
                onStraighten = {
                    if (session.straightenSelection() == 0) scope.launch { snackbar.showSnackbar(nothingToStraighten) }
                },
                onShare = ::shareSelection,
                onSaveImage = ::saveSelectedImages,
                onPlay = ::playSelectedVoice,
                filesAllowed = !locked,
            ),
        )

        // ---- O6: the drawing sheet covers the whole editor while it is open
        val sheet = drawing
        if (sheet != null && newDrawingSession != null) {
            DrawingSheet(
                request = sheet,
                newSession = newDrawingSession,
                loadInk = session::loadDrawingInk,
                settings = settings,
                onDone = { finishDrawing(sheet, it) },
            )
        }

        // ---- Insert > Camera: the live camera sheet over the bottom of the editor (the note stays visible above it)
        if (cameraSheetOpen) {
            org.samesame.notes.feature.camera.CameraSheet(
                outputDir = File(context.cacheDir, "share/camera"),
                onCaptured = { file -> insertImageFrom(ShareFiles.uriFor(context, file)) { file.delete() } },
                onClose = { cameraSheetOpen = false },
                onUnavailable = {
                    cameraSheetOpen = false
                    systemCamera()
                },
                modifier = Modifier.zIndex(3f),
            )
        }

        // ---- WP-J2 full-screen pages ---------------------------------------------------------
        when (pageScreen) {
            ComposerPage.Template -> PageTemplateScreen(
                darkTheme = dark,
                selectedTemplate = (doc.pages.getOrNull(currentPage)?.template as? TemplateRef.Builtin)?.template ?: PageTemplate.Blank,
                onApply = { template, all -> applyTemplate(template, all) },
                onBack = { pageScreen = null },
            )
            ComposerPage.Sorter -> PageSorterScreen(
                pages = doc.pages,
                pageLayout = doc.layout,
                content = { session.content(it) },
                currentIndex = currentPage,
                darkTheme = dark,
                onReorder = { from, to -> reorder(from, to) },
                onAddPage = { addBlankPage() },
                onToggleBookmark = { id -> session.updatePageSpec(id) { it.copy(isBookmarked = !it.isBookmarked) } },
                onDelete = { id -> scope.launch { session.deletePages(setOf(id)) } },
                onOpenPage = { i ->
                    pageScreen = null
                    val r = layout.pageRects.getOrNull(i)
                    if (r != null) viewport.scrollTo(if (layout.horizontal) Offset(r.left, viewport.scroll.y) else Offset(viewport.scroll.x, r.top))
                },
                onBack = { pageScreen = null },
                searchPages = { q ->
                    SearchNavigator.pagesWithHits(org.samesame.notes.core.richtext.TextSearch.find(body.editable, q)) { o -> textState.engine.caret(o).pageIndex }
                },
                onDeleteSelected = { ids -> scope.launch { session.deletePages(ids) } },
                // Copy / Paste use the app clipboard; the locked-note notice covers Share / Save / Copy like the editor's.
                canPaste = { !locked && session.clipboard.hasPages },
                onPaste = { afterIndex -> session.clipboard.pages?.let { clip -> scope.launch { session.pastePages(afterIndex, clip) } } },
                onCopySelected = { ids ->
                    if (locked) lockedSaveNotice = true
                    else scope.launch { session.copyPages(ids)?.let { session.clipboard.setPages(it) } }
                },
                onShareSelected = { ids -> if (locked) lockedSaveNotice = true else pageFileAction = PageFileAction.Share to ids },
                onSaveSelected = { ids -> if (locked) lockedSaveNotice = true else pageFileAction = PageFileAction.Save to ids },
            )
            null -> Unit
        }

        // ---- WP-J2 dialogs -------------------------------------------------------------------
        if (lockedSaveNotice) {
            // Locked notes are never exported: "you need to unlock it first".
            org.samesame.notes.core.designsystem.component.AppDialog(
                onDismissRequest = { lockedSaveNotice = false },
                dismissLabel = stringResource(android.R.string.cancel),
                confirmLabel = stringResource(R.string.feature_composer_unlock),
                onConfirm = { lockedSaveNotice = false; unlockFlow = true },
                atBottom = true,
            ) {
                androidx.compose.material3.Text(
                    stringResource(R.string.feature_composer_save_locked_note),
                    color = SameSameTheme.colors.textPrimary, fontSize = 16.sp,
                )
            }
        }
        if (unlockConfirm) {
            org.samesame.notes.feature.lock.UnlockConfirmDialog(
                count = 1,
                onConfirm = { unlockConfirm = false; unlockFlow = true },
                onDismiss = { unlockConfirm = false },
            )
        }
        org.samesame.notes.feature.lock.LockFlowTransition(lockFlow) {
            // the reference: Lock from the editor asks for the password (created on first use), then the note is locked.
            org.samesame.notes.feature.lock.LockAuthFlow(
                mode = org.samesame.notes.feature.lock.LockAuthMode.VerifyOrCreate,
                onCancel = { lockFlow = false },
                onToken = { token -> lockFlow = false; onLock(token) },
            )
        }
        if (trashConfirm) {
            org.samesame.notes.core.designsystem.component.AppDialog(
                onDismissRequest = { trashConfirm = false },
                dismissLabel = stringResource(android.R.string.cancel),
                confirmLabel = stringResource(R.string.feature_composer_trash_confirm),
                onConfirm = { trashConfirm = false; onMoveToTrash() },
                atBottom = true,
            ) {
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.pluralStringResource(R.plurals.feature_composer_trash_title, 1, 1),
                    color = SameSameTheme.colors.textPrimary, fontSize = 16.sp,
                )
            }
        }
        org.samesame.notes.feature.lock.LockFlowTransition(unlockFlow) {
            org.samesame.notes.feature.lock.LockAuthFlow(
                mode = org.samesame.notes.feature.lock.LockAuthMode.VerifyOnly,
                onCancel = { unlockFlow = false },
                onToken = { unlockFlow = false; onUnlock(it) },
            )
        }
        if (saveAsOpen) {
            SaveAsFileDialog(
                onDismiss = { saveAsOpen = false },
                onConfirm = { format -> saveAsOpen = false; startSaveAs(format) },
            )
        }
        if (shareMenuOpen) ShareFormatDialog(onDismiss = { shareMenuOpen = false }, onPick = { shareMenuOpen = false; shareAs(it) })
        // Page sorter Share / Save as file: the same dialogs without the whole-note containers, for the selected pages.
        pageFileAction?.let { (action, ids) ->
            when (action) {
                PageFileAction.Share -> ShareFormatDialog(
                    onDismiss = { pageFileAction = null },
                    onPick = { pageFileAction = null; shareAs(it, ids) },
                    formats = PageShareFormats,
                )
                PageFileAction.Save -> SaveAsFileDialog(
                    onDismiss = { pageFileAction = null },
                    onConfirm = { format -> pageFileAction = null; startSaveAs(format, ids) },
                    formats = PageSaveFormats,
                )
            }
        }
        if (tagsOpen) TagsDialog(onDismiss = { tagsOpen = false })

    }
}

/**
 * The floating pill and "more" menu of a pen selection. Collects the selection itself, so moving or
 * scaling a selection (a per-pointer-move state update) recomposes this overlay, not the whole editor.
 */
@Composable
private fun SelectionActionOverlay(
    session: EditorSession,
    pages: List<PageSpec>,
    layout: DocumentLayout,
    viewport: Viewport,
    tb: ToolbarState,
    settings: AppSettings,
    currentPage: Int,
    canvasBounds: androidx.compose.runtime.State<androidx.compose.ui.geometry.Rect>,
    onCloseMenu: () -> Unit,
    onEditDrawing: () -> Unit = {},
    fileActions: SelectionFileActions = SelectionFileActions(),
) {
    val sel by session.selectionState.collectAsStateWithLifecycle()
    val selIndex = if (sel.pageId == null) -1 else pages.indexOfFirst { it.id == sel.pageId }
    val selBounds = sel.bounds
    // Pen mode: with the selection tool. Text mode: a tapped / long-pressed object keeps text mode and gets the pill too.
    val toolShowsPill = (tb.mode == EditorMode.Pen && tb.tool == PenModeTool.Selection) || tb.mode == EditorMode.Text
    if (!toolShowsPill || selIndex < 0 || selBounds == null) return
    if (tb.menu != null && tb.menu != Menu.SelectionMore) return
    val density = LocalDensity.current
    val origin = layout.pageOrigin(selIndex)
    val menuOpen = tb.menu == Menu.SelectionMore
    // #50: the pill's bottom sits 46.4 dp above the selection box (platform floating-toolbar margin 8 dp + the content
    // rect grown 38.4 dp for the handles); flipped below, the same gap clears the rotate knob.
    val metrics = remember(density) {
        with(density) { ContextMenuPlacement.Metrics(38.4.dp.toPx(), 16.dp.toPx(), 8.dp.toPx(), 20.dp.toPx()) }
    }
    Layout(
        content = {
            // The pill is always measured (the menu anchors to it) but only placed while the menu is closed.
            SelectionPillFor(session, sel.pageId, pages, layout, viewport, currentPage, menuOpen)
            if (menuOpen) SelectionMenuFor(session, settings, onCloseMenu, onEditDrawing, fileActions)
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val w = constraints.maxWidth; val h = constraints.maxHeight
        val window = androidx.compose.ui.geometry.Rect(0f, 0f, w.toFloat(), h.toFloat())
        val pill = measurables[0].measure(androidx.compose.ui.unit.Constraints(maxWidth = w, maxHeight = h))
        val menu = measurables.getOrNull(1)?.measure(
            androidx.compose.ui.unit.Constraints(maxWidth = w, maxHeight = (h - 2f * metrics.marginV).toInt().coerceAtLeast(0)),
        )
        // Viewport reads happen here, so scrolling relayouts the pill instead of recomposing it.
        val canvas = canvasBounds.value
        val a = viewport.docToView(Offset(origin.x + selBounds.left, origin.y + selBounds.top))
        val b = viewport.docToView(Offset(origin.x + selBounds.right, origin.y + selBounds.bottom))
        val selRect = androidx.compose.ui.geometry.Rect(
            minOf(a.x, b.x) + canvas.left, minOf(a.y, b.y) + canvas.top, maxOf(a.x, b.x) + canvas.left, maxOf(a.y, b.y) + canvas.top,
        )
        val placed = ContextMenuPlacement.placePill(selRect, canvas, window, pill.width.toFloat(), pill.height.toFloat(), metrics)
        layout(w, h) {
            if (placed == null) return@layout
            if (menu == null) {
                pill.place(placed.position.x.roundToInt(), placed.position.y.roundToInt())
            } else {
                val p = ContextMenuPlacement.placeMenu(
                    placed, pill.width.toFloat(), pill.height.toFloat(), menu.width.toFloat(), menu.height.toFloat(), window, metrics,
                )
                menu.place(p.x.roundToInt(), p.y.roundToInt())
            }
        }
    }
}

@Composable
private fun SelectionPillFor(
    session: EditorSession,
    selPageId: String?,
    pages: List<PageSpec>,
    layout: DocumentLayout,
    viewport: Viewport,
    currentPage: Int,
    moreOpen: Boolean,
) {
    SelectionPill(
        canPaste = session.clipboard.hasContent,
        onCut = { session.cutSelection() },
        onCopy = { session.copySelection() },
        onPaste = {
            val pageId = selPageId ?: pages.getOrNull(currentPage)?.id
            if (pageId != null) {
                val i = pages.indexOfFirst { it.id == pageId }.coerceAtLeast(0)
                session.paste(pageId, originVisible = false, viewportCenterInPage = viewport.visibleDoc.center - layout.pageOrigin(i))
            }
        },
        onDelete = { session.deleteSelection() },
        onMore = { session.dispatch(ToolbarIntent.OpenMenu(Menu.SelectionMore)) },
        moreOpen = moreOpen,
    )
}

/** The selection's more menu (#51): the object menu when the selection holds objects (images, text boxes, voice). */
@Composable
private fun SelectionMenuFor(
    session: EditorSession,
    settings: AppSettings,
    onCloseMenu: () -> Unit,
    onEditDrawing: () -> Unit,
    fileActions: SelectionFileActions,
) {
    val menu = session.objectMenuState(Limits.PAGE_WIDTH_PU)
    // Each of these closes the menu first (the reference keeps its context menu open only for Select all).
    fun close(action: () -> Unit): () -> Unit = { onCloseMenu(); action() }
    val extras = ObjectMenuExtras(
        onCrop = if (menu.showCrop) close(fileActions.onCrop) else null,
        onLassoCrop = if (menu.showLassoCrop) close(fileActions.onLassoCrop) else null,
        onEditImage = if (menu.showEditImage && fileActions.filesAllowed) close(fileActions.onEditImage) else null,
        onAnchor = if (menu.showAnchor) close(fileActions.onAnchor) else null,
        onStraighten = if (menu.showChangeStyle) close(fileActions.onStraighten) else null,
        onChangeStyle = if (menu.showChangeStyle) ({ session.dispatch(ToolbarIntent.OpenChangeStyle) }) else null,
        onShare = if (menu.showShare && fileActions.filesAllowed) close(fileActions.onShare) else null,
        onSaveImage = if (menu.showSaveImage && fileActions.filesAllowed) close(fileActions.onSaveImage) else null,
        onPlay = if (menu.showPlay) close(fileActions.onPlay) else null,
    )
    val behind = menu.behind
    if (menu.isObjects) {
        ObjectMoreMenu(
            showFitWidth = menu.showFitWidth,
            showWrap = menu.showWrap,
            behind = behind,
            // the reference closes its context menu after these three (only Select All keeps it open).
            onResizeDefault = { session.resizeSelectionToDefault(); onCloseMenu() },
            onFitWidth = { session.fitSelectionToPageWidth(Limits.PAGE_WIDTH_PU); onCloseMenu() },
            onToggleWrap = { session.setSelectionWrap(if (behind) TextWrap.InFront else TextWrap.Behind); onCloseMenu() },
            onForward = { session.moveSelectionForward(); onCloseMenu() },
            onBackward = { session.moveSelectionBackward(); onCloseMenu() },
            onBringToFront = { session.bringSelectionToFront(); onCloseMenu() },
            onSendToBack = { session.sendSelectionToBack(); onCloseMenu() },
            onBack = { onCloseMenu() },
            showResize = menu.showResize,
            showDrawing = menu.showDrawing,
            onDrawing = { onEditDrawing(); onCloseMenu() },
            actions = extras,
        )
    } else {
        SelectionMoreMenu(
            onChangeStyle = { session.dispatch(ToolbarIntent.OpenChangeStyle) },
            onForward = { session.moveSelectionForward(); onCloseMenu() },
            onBackward = { session.moveSelectionBackward(); onCloseMenu() },
            onBack = { onCloseMenu() },
            showChangeStyle = settings.toolbarChangeStyle,
            onStraighten = close(fileActions.onStraighten),
        )
    }
}

/** An image handed to an outside editor: where it sits, the cache copy the editor may write, and the bytes it had. */
private class PendingImageEdit(val pageId: String, val id: org.samesame.notes.core.ink.page.ElementId, val file: File, val original: ByteArray)

/** Object-menu actions that need the screen (files, crop mode, playback); [filesAllowed] is false for a locked note. */
private class SelectionFileActions(
    val onCrop: () -> Unit = {},
    val onLassoCrop: () -> Unit = {},
    val onEditImage: () -> Unit = {},
    val onAnchor: () -> Unit = {},
    val onStraighten: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onSaveImage: () -> Unit = {},
    val onPlay: () -> Unit = {},
    val filesAllowed: Boolean = true,
)

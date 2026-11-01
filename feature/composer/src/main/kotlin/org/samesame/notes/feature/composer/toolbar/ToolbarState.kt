package org.samesame.notes.feature.composer.toolbar

/** Top-level editor mode. [Reading] is the book icon in the app bar. */
enum class EditorMode { Text, Pen, Reading }

/** Tools of the pen toolbar (`#32`). */
enum class PenModeTool { Pen, Highlighter, Eraser, Selection }

/** What the colour picker dialog is editing; used to route the result. */
enum class ColorTarget { FontColor, Highlight, Pen, Highlighter, ChangeStyle }

/** Floating card above the toolbar. At most one is open. */
sealed interface Panel {
    data object TextOptions : Panel
    data object FontColor : Panel
    data object Highlight : Panel
    data object FontSize : Panel
    data object PenSettings : Panel
    data object HighlighterSettings : Panel
    data object Eraser : Panel
    data object SelectionMode : Panel
    data object ChangeStyle : Panel

    /** The picker dialog; [returnTo] is the panel that opened it and is restored on Cancel/Done. */
    data class ColorPicker(val target: ColorTarget, val returnTo: Panel?) : Panel
}

enum class Menu { Insert, Overflow, SelectionMore, PageSettings }

data class ToolbarState(
    val mode: EditorMode = EditorMode.Text,
    val tool: PenModeTool = PenModeTool.Pen,
    val panel: Panel? = null,
    val menu: Menu? = null,
    val fullScreen: Boolean = false,
    /** Mode to return to when reading mode ends. */
    val modeBeforeReading: EditorMode = EditorMode.Text,
    /**
     * The view state (with [mode] = Reading): how an existing note opens. Header pencil Edit + Search + more; a tap on the
     * body enters Text mode with the caret there ("touch to edit"). False for the book-icon reading mode (read only).
     */
    val viewMode: Boolean = false,
    /** Editing that started from the view state: Back returns to it (after the keyboard) instead of leaving. */
    val fromView: Boolean = false,
)

sealed interface ToolbarIntent {
    /** Handwriting/keyboard toggle at the left end of both toolbars. */
    data object ToggleHandwriting : ToolbarIntent
    data object EnterReading : ToolbarIntent
    data object ExitReading : ToolbarIntent

    /** A stylus tap in the view state: edit in pen mode (the reference's touch-to-edit picks Writing for a pen). */
    data object ExitViewWithPen : ToolbarIntent

    /** Tap on a pen-mode tool: inactive selects it and closes the panel; active toggles its panel. */
    data class TapTool(val tool: PenModeTool) : ToolbarIntent

    /** Tap on a text-toolbar button that owns a panel; tapping the open one closes it. */
    data class TogglePanel(val panel: Panel) : ToolbarIntent
    data object ClosePanel : ToolbarIntent

    data class OpenColorPicker(val target: ColorTarget) : ToolbarIntent
    data object CloseColorPicker : ToolbarIntent

    /** Selection pill "Change style" (`#51` -> `#52`). Only valid with the selection tool. */
    data object OpenChangeStyle : ToolbarIntent

    data class OpenMenu(val menu: Menu) : ToolbarIntent
    data object CloseMenu : ToolbarIntent
    data class SetFullScreen(val on: Boolean) : ToolbarIntent

    /** System back; [hasSelection] is owned by the selection controller. */
    data class Back(val hasSelection: Boolean) : ToolbarIntent
}

/** What a back press does; order: panel, menu, selection, full screen, reading, back to the view state, leave. */
enum class BackAction { ClosePanel, CloseMenu, ClearSelection, ExitFullScreen, ExitReading, ReturnToView, LeaveEditor }

object ToolbarReducer {

    fun reduce(state: ToolbarState, intent: ToolbarIntent): ToolbarState = when (intent) {
        ToolbarIntent.ToggleHandwriting -> when (state.mode) {
            EditorMode.Text -> state.copy(mode = EditorMode.Pen, panel = null, menu = null)
            EditorMode.Pen -> state.copy(mode = EditorMode.Text, panel = null, menu = null)
            EditorMode.Reading -> state
        }

        ToolbarIntent.EnterReading ->
            if (state.mode == EditorMode.Reading) state
            else state.copy(mode = EditorMode.Reading, modeBeforeReading = state.mode, panel = null, menu = null, viewMode = false)

        ToolbarIntent.ExitReading ->
            if (state.mode != EditorMode.Reading) state
            else state.copy(mode = state.modeBeforeReading, panel = null, menu = null, viewMode = false, fromView = state.fromView || state.viewMode)

        ToolbarIntent.ExitViewWithPen ->
            if (state.mode != EditorMode.Reading || !state.viewMode) state
            else state.copy(mode = EditorMode.Pen, panel = null, menu = null, viewMode = false, fromView = true)

        is ToolbarIntent.TapTool ->
            if (state.mode != EditorMode.Pen) state
            else if (state.tool != intent.tool) state.copy(tool = intent.tool, panel = null, menu = null)
            else {
                val own = panelFor(intent.tool)
                state.copy(panel = if (state.panel == own) null else own, menu = null)
            }

        is ToolbarIntent.TogglePanel ->
            if (!allowed(state, intent.panel)) state
            else state.copy(panel = if (state.panel == intent.panel) null else intent.panel, menu = null)

        ToolbarIntent.ClosePanel -> state.copy(panel = null)

        is ToolbarIntent.OpenColorPicker -> state.copy(
            panel = Panel.ColorPicker(intent.target, returnTo = state.panel),
            menu = null,
        )

        ToolbarIntent.CloseColorPicker -> {
            val p = state.panel
            if (p is Panel.ColorPicker) state.copy(panel = p.returnTo) else state
        }

        ToolbarIntent.OpenChangeStyle ->
            if (state.mode == EditorMode.Pen && state.tool == PenModeTool.Selection) {
                state.copy(panel = Panel.ChangeStyle, menu = null)
            } else state

        // Reading mode has only one menu: the overflow (More) with the reading entries.
        is ToolbarIntent.OpenMenu ->
            if (state.mode == EditorMode.Reading && intent.menu != Menu.Overflow) state
            else state.copy(menu = intent.menu, panel = null)

        ToolbarIntent.CloseMenu -> state.copy(menu = null)
        is ToolbarIntent.SetFullScreen -> state.copy(fullScreen = intent.on, panel = null, menu = null)

        is ToolbarIntent.Back -> when (backAction(state, intent.hasSelection)) {
            BackAction.ClosePanel -> {
                val p = state.panel
                state.copy(panel = if (p is Panel.ColorPicker) p.returnTo else null)
            }
            BackAction.CloseMenu -> state.copy(menu = null)
            BackAction.ExitFullScreen -> state.copy(fullScreen = false)
            BackAction.ExitReading -> state.copy(mode = state.modeBeforeReading, viewMode = false)
            BackAction.ReturnToView -> state.copy(
                mode = EditorMode.Reading, modeBeforeReading = EditorMode.Text, viewMode = true, fromView = false, panel = null, menu = null,
            )
            // The selection and the editor itself live outside this state.
            BackAction.ClearSelection, BackAction.LeaveEditor -> state
        }
    }

    fun backAction(state: ToolbarState, hasSelection: Boolean): BackAction = when {
        state.panel != null -> BackAction.ClosePanel
        state.menu != null -> BackAction.CloseMenu
        hasSelection -> BackAction.ClearSelection
        state.fullScreen -> BackAction.ExitFullScreen
        // The view state is the top level: Back leaves. The book-icon reading mode goes back to editing.
        state.mode == EditorMode.Reading -> if (state.viewMode) BackAction.LeaveEditor else BackAction.ExitReading
        state.fromView -> BackAction.ReturnToView
        else -> BackAction.LeaveEditor
    }

    fun panelFor(tool: PenModeTool): Panel = when (tool) {
        PenModeTool.Pen -> Panel.PenSettings
        PenModeTool.Highlighter -> Panel.HighlighterSettings
        PenModeTool.Eraser -> Panel.Eraser
        PenModeTool.Selection -> Panel.SelectionMode
    }

    private fun allowed(state: ToolbarState, panel: Panel): Boolean = when (panel) {
        Panel.TextOptions, Panel.FontColor, Panel.Highlight, Panel.FontSize -> state.mode == EditorMode.Text
        Panel.PenSettings, Panel.HighlighterSettings, Panel.Eraser, Panel.SelectionMode, Panel.ChangeStyle ->
            state.mode == EditorMode.Pen
        is Panel.ColorPicker -> true
    }
}

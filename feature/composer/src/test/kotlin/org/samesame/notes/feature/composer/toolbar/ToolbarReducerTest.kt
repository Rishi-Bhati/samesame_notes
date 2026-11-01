package org.samesame.notes.feature.composer.toolbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolbarReducerTest {
    private val pen = ToolbarState(mode = EditorMode.Pen)

    private fun ToolbarState.on(i: ToolbarIntent) = ToolbarReducer.reduce(this, i)

    @Test fun toggleHandwritingSwitchesModeAndClosesPanel() {
        val s = ToolbarState(panel = Panel.TextOptions).on(ToolbarIntent.ToggleHandwriting)
        assertEquals(EditorMode.Pen, s.mode)
        assertNull(s.panel)
        assertEquals(EditorMode.Text, s.on(ToolbarIntent.ToggleHandwriting).mode)
    }

    @Test fun tappingInactiveToolSelectsItAndClosesPanel() {
        val s = pen.copy(panel = Panel.PenSettings).on(ToolbarIntent.TapTool(PenModeTool.Eraser))
        assertEquals(PenModeTool.Eraser, s.tool)
        assertNull(s.panel)
    }

    @Test fun tappingActiveToolTogglesItsPanel() {
        val open = pen.on(ToolbarIntent.TapTool(PenModeTool.Pen))
        assertEquals(Panel.PenSettings, open.panel)
        assertNull(open.on(ToolbarIntent.TapTool(PenModeTool.Pen)).panel)
        val e = pen.on(ToolbarIntent.TapTool(PenModeTool.Eraser)).on(ToolbarIntent.TapTool(PenModeTool.Eraser))
        assertEquals(Panel.Eraser, e.panel)
        val sel = pen.on(ToolbarIntent.TapTool(PenModeTool.Selection)).on(ToolbarIntent.TapTool(PenModeTool.Selection))
        assertEquals(Panel.SelectionMode, sel.panel)
    }

    @Test fun textPanelsToggleAndAreTextModeOnly() {
        val s = ToolbarState().on(ToolbarIntent.TogglePanel(Panel.FontColor))
        assertEquals(Panel.FontColor, s.panel)
        assertEquals(Panel.Highlight, s.on(ToolbarIntent.TogglePanel(Panel.Highlight)).panel)
        assertNull(s.on(ToolbarIntent.TogglePanel(Panel.FontColor)).panel)
        assertNull(pen.on(ToolbarIntent.TogglePanel(Panel.TextOptions)).panel)
    }

    @Test fun colorPickerReturnsToOpeningPanel() {
        val s = pen.on(ToolbarIntent.TapTool(PenModeTool.Pen))
            .on(ToolbarIntent.OpenColorPicker(ColorTarget.Pen))
        assertEquals(Panel.ColorPicker(ColorTarget.Pen, Panel.PenSettings), s.panel)
        assertEquals(Panel.PenSettings, s.on(ToolbarIntent.CloseColorPicker).panel)
        assertEquals(Panel.PenSettings, s.on(ToolbarIntent.Back(false)).panel)
    }

    @Test fun changeStyleNeedsSelectionTool() {
        assertNull(pen.on(ToolbarIntent.OpenChangeStyle).panel)
        val s = pen.copy(tool = PenModeTool.Selection, menu = Menu.SelectionMore).on(ToolbarIntent.OpenChangeStyle)
        assertEquals(Panel.ChangeStyle, s.panel)
        assertNull(s.menu)
    }

    @Test fun backOrderIsPanelMenuSelectionFullScreenReadingLeave() {
        var s = pen.copy(panel = Panel.Eraser, menu = Menu.Overflow, fullScreen = true)
        assertEquals(BackAction.ClosePanel, ToolbarReducer.backAction(s, true))
        s = s.on(ToolbarIntent.Back(true)); assertNull(s.panel)
        assertEquals(BackAction.CloseMenu, ToolbarReducer.backAction(s, true))
        s = s.on(ToolbarIntent.Back(true)); assertNull(s.menu)
        assertEquals(BackAction.ClearSelection, ToolbarReducer.backAction(s, true))
        assertEquals(BackAction.ExitFullScreen, ToolbarReducer.backAction(s, false))
        s = s.on(ToolbarIntent.Back(false)); assertEquals(false, s.fullScreen)
        assertEquals(BackAction.LeaveEditor, ToolbarReducer.backAction(s, false))
    }

    @Test fun readingRemembersPreviousMode() {
        val r = pen.on(ToolbarIntent.EnterReading)
        assertEquals(EditorMode.Reading, r.mode)
        assertEquals(BackAction.ExitReading, ToolbarReducer.backAction(r, false))
        assertEquals(EditorMode.Pen, r.on(ToolbarIntent.Back(false)).mode)
        assertEquals(EditorMode.Pen, r.on(ToolbarIntent.ExitReading).mode)
    }

    @Test fun viewModeIsOnlyTheOpenedNotesReadingMode() {
        val opened = ToolbarState(mode = EditorMode.Reading, viewMode = true)
        val edited = opened.on(ToolbarIntent.ExitReading)
        assertEquals(EditorMode.Text, edited.mode)
        assertEquals(false, edited.viewMode)
        // The book icon enters the read-only reading mode: no touch to edit.
        assertEquals(false, edited.on(ToolbarIntent.EnterReading).viewMode)
    }

    @Test fun backFromAnEditStartedInTheViewStateReturnsToIt() {
        val opened = ToolbarState(mode = EditorMode.Reading, viewMode = true)
        // The view state is the top level: Back leaves the note.
        assertEquals(BackAction.LeaveEditor, ToolbarReducer.backAction(opened, false))
        val edited = opened.on(ToolbarIntent.ExitReading)
        assertEquals(true, edited.fromView)
        assertEquals(BackAction.ReturnToView, ToolbarReducer.backAction(edited, false))
        val back = edited.on(ToolbarIntent.Back(false))
        assertEquals(EditorMode.Reading, back.mode)
        assertEquals(true, back.viewMode)
        assertEquals(false, back.fromView)
        // The book-icon reading mode inside that edit returns to the edit, which still returns to the view state.
        val book = edited.on(ToolbarIntent.EnterReading)
        assertEquals(BackAction.ExitReading, ToolbarReducer.backAction(book, false))
        assertEquals(BackAction.ReturnToView, ToolbarReducer.backAction(book.on(ToolbarIntent.Back(false)), false))
        // A note created with the FAB has no view state: Back leaves.
        assertEquals(BackAction.LeaveEditor, ToolbarReducer.backAction(ToolbarState(), false))
    }

    @Test fun moreOpensTheReadingOverflowButInsertStaysClosedInReading() {
        val r = pen.on(ToolbarIntent.EnterReading)
        assertEquals(Menu.Overflow, r.on(ToolbarIntent.OpenMenu(Menu.Overflow)).menu)
        assertNull(r.on(ToolbarIntent.OpenMenu(Menu.Insert)).menu)
        assertNull(r.on(ToolbarIntent.OpenMenu(Menu.Overflow)).on(ToolbarIntent.CloseMenu).menu)
    }

    @Test fun menusOpenAndCloseOtherPanels() {
        val s = pen.copy(panel = Panel.Eraser).on(ToolbarIntent.OpenMenu(Menu.Overflow))
        assertEquals(Menu.Overflow, s.menu)
        assertNull(s.panel)
    }

    @Test fun aStylusTapInTheViewStateEditsInPenMode() {
        val opened = ToolbarState(mode = EditorMode.Reading, viewMode = true)
        val pen = opened.on(ToolbarIntent.ExitViewWithPen)
        assertEquals(EditorMode.Pen, pen.mode)
        assertEquals(false, pen.viewMode)
        assertEquals(true, pen.fromView)
        assertEquals(BackAction.ReturnToView, ToolbarReducer.backAction(pen, false))
        // Only from the view state: book reading and edit modes ignore it.
        val book = ToolbarState(mode = EditorMode.Reading, viewMode = false)
        assertEquals(book, book.on(ToolbarIntent.ExitViewWithPen))
        val text = ToolbarState(mode = EditorMode.Text)
        assertEquals(text, text.on(ToolbarIntent.ExitViewWithPen))
    }
}

package org.samesame.notes.feature.composer.toolbar

import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolbarStateSaverTest {

    private fun roundTrip(state: ToolbarState): ToolbarState? =
        ToolbarStateSaver.restore(with(ToolbarStateSaver) { SaverScope { true }.save(state) })

    @Test
    fun defaultStateRoundTrips() {
        assertThat(roundTrip(ToolbarState())).isEqualTo(ToolbarState())
    }

    @Test
    fun modeToolFullScreenAndViewStateRoundTrip() {
        val state = ToolbarState(
            mode = EditorMode.Pen,
            tool = PenModeTool.Highlighter,
            fullScreen = true,
            modeBeforeReading = EditorMode.Text,
            viewMode = false,
            fromView = true,
        )
        assertThat(roundTrip(state)).isEqualTo(state)
    }

    @Test
    fun everyPanelAndMenuRoundTrips() {
        val panels = listOf(
            Panel.TextOptions, Panel.FontColor, Panel.Highlight, Panel.FontSize,
            Panel.PenSettings, Panel.HighlighterSettings, Panel.Eraser, Panel.SelectionMode, Panel.ChangeStyle,
        )
        for (panel in panels) {
            val state = ToolbarState(mode = EditorMode.Text, panel = panel)
            assertThat(roundTrip(state)).isEqualTo(state)
        }
        for (menu in Menu.entries) {
            val state = ToolbarState(menu = menu)
            assertThat(roundTrip(state)).isEqualTo(state)
        }
    }

    @Test
    fun colourPickerKeepsItsTargetAndReturnPanel() {
        val state = ToolbarState(
            mode = EditorMode.Pen,
            tool = PenModeTool.Pen,
            panel = Panel.ColorPicker(ColorTarget.Pen, returnTo = Panel.PenSettings),
        )
        assertThat(roundTrip(state)).isEqualTo(state)
    }

    @Test
    fun restoreIntentReplacesTheState() {
        val saved = ToolbarState(mode = EditorMode.Pen, tool = PenModeTool.Eraser, menu = Menu.Insert)
        assertThat(ToolbarReducer.reduce(ToolbarState(mode = EditorMode.Reading, viewMode = true), ToolbarIntent.Restore(saved)))
            .isEqualTo(saved)
    }

    @Test
    fun unknownSavedValueIsIgnored() {
        assertThat(ToolbarStateSaver.restore("not a toolbar")).isNull()
        assertThat(ToolbarStateSaver.restore(arrayListOf("Pen"))).isNull()
    }
}

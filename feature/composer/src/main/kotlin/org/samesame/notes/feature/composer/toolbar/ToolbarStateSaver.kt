package org.samesame.notes.feature.composer.toolbar

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope

/**
 * Saves the editor's [ToolbarState] (mode, tool, open panel/menu, full screen) so a recreated activity (theme, font
 * scale, locale, display size) or a process that died in the background reopens the editor in the state the user left
 * it in. Only plain values are stored: nothing here keeps a session or a UI object alive.
 */
object ToolbarStateSaver : Saver<ToolbarState, Any> {

    override fun SaverScope.save(value: ToolbarState): Any = arrayListOf(
        value.mode.name,
        value.tool.name,
        panelCode(value.panel),
        value.menu?.name.orEmpty(),
        value.fullScreen,
        value.modeBeforeReading.name,
        value.viewMode,
        value.fromView,
    )

    override fun restore(value: Any): ToolbarState? {
        val parts = value as? List<*> ?: return null
        if (parts.size != PART_COUNT) return null
        return ToolbarState(
            mode = enumOr<EditorMode>(parts[0]) ?: EditorMode.Text,
            tool = enumOr<PenModeTool>(parts[1]) ?: PenModeTool.Pen,
            panel = panelFromCode(parts[2] as? String),
            menu = enumOr<Menu>(parts[3]),
            fullScreen = parts[4] as? Boolean ?: false,
            modeBeforeReading = enumOr<EditorMode>(parts[5]) ?: EditorMode.Text,
            viewMode = parts[6] as? Boolean ?: false,
            fromView = parts[7] as? Boolean ?: false,
        )
    }

    private const val PART_COUNT = 8

    private inline fun <reified E : Enum<E>> enumOr(raw: Any?): E? = enumValues<E>().firstOrNull { it.name == raw }

    /** `""` = no panel, `"panel:<Kind>"`, or `"picker:<target>:<return-code>"` (nested). */
    private fun panelCode(panel: Panel?): String = when (panel) {
        null -> ""
        is Panel.ColorPicker -> "picker:${panel.target.name}:${panelCode(panel.returnTo)}"
        Panel.TextOptions -> "panel:TextOptions"
        Panel.FontColor -> "panel:FontColor"
        Panel.Highlight -> "panel:Highlight"
        Panel.FontSize -> "panel:FontSize"
        Panel.PenSettings -> "panel:PenSettings"
        Panel.HighlighterSettings -> "panel:HighlighterSettings"
        Panel.Eraser -> "panel:Eraser"
        Panel.SelectionMode -> "panel:SelectionMode"
        Panel.ChangeStyle -> "panel:ChangeStyle"
    }

    private fun panelFromCode(code: String?): Panel? {
        if (code.isNullOrEmpty()) return null
        if (code.startsWith(PICKER)) {
            val rest = code.removePrefix(PICKER)
            val sep = rest.indexOf(':')
            if (sep < 0) return null
            val target = enumOr<ColorTarget>(rest.substring(0, sep)) ?: return null
            return Panel.ColorPicker(target, panelFromCode(rest.substring(sep + 1)))
        }
        if (!code.startsWith(PANEL)) return null
        return when (code.removePrefix(PANEL)) {
            "TextOptions" -> Panel.TextOptions
            "FontColor" -> Panel.FontColor
            "Highlight" -> Panel.Highlight
            "FontSize" -> Panel.FontSize
            "PenSettings" -> Panel.PenSettings
            "HighlighterSettings" -> Panel.HighlighterSettings
            "Eraser" -> Panel.Eraser
            "SelectionMode" -> Panel.SelectionMode
            "ChangeStyle" -> Panel.ChangeStyle
            else -> null
        }
    }

    private const val PICKER = "picker:"
    private const val PANEL = "panel:"
}

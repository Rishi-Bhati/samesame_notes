package org.samesame.notes.feature.composer.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.samesame.notes.core.data.repository.EditorPreferences
import org.samesame.notes.core.data.repository.EditorPreferencesRepository
import org.samesame.notes.feature.composer.panels.EraserMode
import org.samesame.notes.feature.composer.panels.SelectionShape
import org.samesame.notes.feature.composer.toolbar.ToolbarIntent
import org.samesame.notes.feature.composer.toolbar.ToolbarReducer
import org.samesame.notes.feature.composer.toolbar.ToolbarState

/** Per-tool settings (pen, highlighter, eraser, selection), shared by all notes and persisted. */
data class ToolsState(
    val prefs: EditorPreferences = EditorPreferences(),
) {
    /** Eraser radius in page units from the 1..10 size slider (#83-#85); size 1 keeps the earlier 24 pu. NOTE(WP-H): provisional scale. */
    val eraserRadiusPu: Float get() = DEFAULT_ERASER_RADIUS_PU + ERASER_RADIUS_STEP_PU * (prefs.eraserSize.coerceIn(1, 10) - 1)

    val eraserMode: EraserMode get() = enumOrDefault(prefs.eraserMode, EraserMode.Stroke)
    val selectionShape: SelectionShape get() = enumOrDefault(prefs.selectionMode, SelectionShape.Lasso)

    companion object {
        const val DEFAULT_ERASER_RADIUS_PU = 24f
        const val ERASER_RADIUS_STEP_PU = 8f
    }
}

private inline fun <reified E : Enum<E>> enumOrDefault(name: String, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default

/**
 * Wraps the toolbar reducer (WP-I) and the persisted tool settings. Mode/tool switches call [onModeOrToolChanged]
 * (history uses it to end a typing burst).
 */
class ToolController(
    private val scope: CoroutineScope,
    private val repo: EditorPreferencesRepository,
    initial: ToolbarState = ToolbarState(),
    private val onModeOrToolChanged: () -> Unit = {},
) {
    private val _toolbar = MutableStateFlow(initial)
    val toolbar: StateFlow<ToolbarState> = _toolbar.asStateFlow()

    private val _tools = MutableStateFlow(ToolsState())
    val tools: StateFlow<ToolsState> = _tools.asStateFlow()

    init {
        scope.launch { repo.preferences.collect { p -> _tools.value = _tools.value.copy(prefs = p) } }
    }

    /** Applies [intent]; [hasSelection] feeds the back-press order. */
    fun dispatch(intent: ToolbarIntent): ToolbarState {
        val old = _toolbar.value
        val new = ToolbarReducer.reduce(old, intent)
        if (new != old) {
            _toolbar.value = new
            if (new.mode != old.mode || new.tool != old.tool) onModeOrToolChanged()
        }
        return new
    }

    /** Optimistically updates the local state, then persists. */
    fun updatePrefs(transform: (EditorPreferences) -> EditorPreferences) {
        _tools.value = _tools.value.let { it.copy(prefs = transform(it.prefs)) }
        scope.launch { repo.update(transform) }
    }

    fun addRecentColor(argb: Long) {
        updatePrefs { it.copy(recentColors = (listOf(argb) + (it.recentColors - argb)).take(EditorPreferences.MAX_RECENT_COLORS)) }
    }

    fun setEraserMode(mode: EraserMode) = updatePrefs { it.copy(eraserMode = mode.name) }
    fun setSelectionShape(shape: SelectionShape) = updatePrefs { it.copy(selectionMode = shape.name) }
}

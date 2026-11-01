package org.samesame.notes.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.samesame.notes.core.model.PenType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A saved pen (the star in the pen panel). [highlighter] pens carry [tip] (0..3) and [opacity]; ink pens carry [penType].
 * Two favourites are equal when kind, tip/type, size level and colour (and opacity) match, so a pen can only be saved once.
 */
data class FavoritePen(
    val highlighter: Boolean,
    val penType: PenType = PenType.Fountain,
    val tip: Int = 0,
    val level: Int,
    val color: Long,
    val opacity: Int = 100,
)

enum class FavoriteAddResult { Added, AlreadySaved, ListFull }

object FavoritePens {
    /** The reference app keeps at most 45 favourite pens. */
    const val MAX = 45

    fun add(list: List<FavoritePen>, pen: FavoritePen): Pair<List<FavoritePen>, FavoriteAddResult> = when {
        pen in list -> list to FavoriteAddResult.AlreadySaved
        list.size >= MAX -> list to FavoriteAddResult.ListFull
        else -> (list + pen) to FavoriteAddResult.Added
    }

    fun encode(list: List<FavoritePen>): String = list.joinToString(";") {
        listOf(if (it.highlighter) "H" else "P", it.penType.name, it.tip, it.level, it.color, it.opacity).joinToString(":")
    }

    fun decode(raw: String?): List<FavoritePen> = raw.orEmpty().split(';').mapNotNull { row ->
        val f = row.split(':')
        if (f.size < 6) return@mapNotNull null
        val type = PenType.entries.firstOrNull { it.name == f[1] } ?: PenType.Fountain
        FavoritePen(
            highlighter = f[0] == "H", penType = type, tip = f[2].toIntOrNull() ?: 0, level = f[3].toIntOrNull()?.coerceIn(1, 100) ?: return@mapNotNull null,
            color = f[4].toLongOrNull() ?: return@mapNotNull null, opacity = f[5].toIntOrNull()?.coerceIn(1, 100) ?: 100,
        )
    }.distinct().take(MAX)
}

/**
 * Editor tool settings remembered across notes (docs 5: per-tool settings).
 * NOTE(WP-D): eraser/selection modes are stored as strings (their enums belong to the tools package
 * and are not in core:model); WP-H maps them. Levels are the pen-catalog UI levels.
 */
data class EditorPreferences(
    val penType: PenType = PenType.Fountain, // #33: first tip (fountain) is selected by default
    val penLevel: Int = 30,
    val penColor: Long = 0xFF335FFF, // #33: slider tint of the reference default pen
    val penPressureSensitive: Boolean = true,
    val highlighterLevel: Int = 50,
    /** 0..3: highlighter, straight-line highlighter, marker pen, straight-line marker (#81). */
    val highlighterTip: Int = 0,
    /** Highlighter opacity in percent (#81: 45). */
    val highlighterOpacity: Int = 45,
    /** #FFB426, drawn at [highlighterOpacity] (reference default highlighter). */
    val highlighterColor: Long = 0xFFFFB426,
    val eraserMode: String = "Stroke",
    val eraserHighlighterOnly: Boolean = false,
    /** Area-eraser size 1..10, default 1 (#83-#85). */
    val eraserSize: Int = 1,
    val selectionMode: String = "Lasso",
    val selectionIncludePartial: Boolean = true,
    /** Most recent first, capped at [MAX_RECENT_COLORS]. */
    val recentColors: List<Long> = emptyList(),
    /** Palette set ids (1..21) shown after the Recent page, in page order ("Select colour sets to show"; 1..4 of them). */
    val paletteSets: List<Int> = DEFAULT_PALETTE_SETS,
    /** Favourite pens in display order (max [FavoritePens.MAX]). */
    val favoritePens: List<FavoritePen> = emptyList(),
    /** The easy writing pad's guide text is shown until the pad has been used once. */
    val easyPadGuideSeen: Boolean = false,
    /** The one-time "Tap Title to add a title..." tip of a new note (reference: `pref_key_body_guide_tip`) has been shown and dismissed. */
    val bodyGuideSeen: Boolean = false,
    /** Finger drawing: on until a stylus has been seen on the device (then switched off once, see [withStylusSeen]); the user can re-enable it. */
    val fingerDrawing: Boolean = true,
    /** A stylus event has been seen on this device (persisted); finger drawing was switched off at that moment. */
    val stylusSeen: Boolean = false,
    val zoomLock: Boolean = false,
) {
    /** First stylus event: remember it and turn finger drawing off (once; later toggles by the user stick). */
    fun withStylusSeen(): EditorPreferences = if (stylusSeen) this else copy(stylusSeen = true, fingerDrawing = false)

    companion object {
        /** Recent-colour capacity of the reference app; the pen panel shows 7 of them when the eyedropper cell is present. */
        const val MAX_RECENT_COLORS = 8
        const val MAX_PALETTE_SETS = 4
        const val PALETTE_SET_COUNT = 21
        val DEFAULT_PALETTE_SETS = listOf(1, 4, 21, 5)
    }
}

interface EditorPreferencesRepository {
    val preferences: Flow<EditorPreferences>

    /** Atomically applies [transform] to the stored preferences. */
    suspend fun update(transform: (EditorPreferences) -> EditorPreferences)

    /** Moves [argb] to the front of the recent colours. */
    suspend fun addRecentColor(argb: Long)
}

private val Context.editorPreferencesStore: DataStore<Preferences> by preferencesDataStore(name = "editor_preferences")

private object EditorKeys {
    val penType = stringPreferencesKey("pen_type")
    val penLevel = intPreferencesKey("pen_level_v2")
    val penColor = longPreferencesKey("pen_color")
    val penPressure = booleanPreferencesKey("pen_pressure")
    val hlLevel = intPreferencesKey("highlighter_level_v2")
    val hlTip = intPreferencesKey("highlighter_tip")
    val hlOpacity = intPreferencesKey("highlighter_opacity")
    val eraserSize = intPreferencesKey("eraser_size")
    val hlColor = longPreferencesKey("highlighter_color")
    val eraserMode = stringPreferencesKey("eraser_mode")
    val eraserHlOnly = booleanPreferencesKey("eraser_highlighter_only")
    val selMode = stringPreferencesKey("selection_mode")
    val selPartial = booleanPreferencesKey("selection_include_partial")
    val recent = stringPreferencesKey("recent_colors")
    val paletteSets = stringPreferencesKey("palette_sets")
    val favoritePens = stringPreferencesKey("favorite_pens")
    val finger = booleanPreferencesKey("finger_drawing")
    val stylusSeen = booleanPreferencesKey("stylus_seen")
    val easyPadGuideSeen = booleanPreferencesKey("easy_pad_guide_seen")
    val bodyGuideSeen = booleanPreferencesKey("body_guide_seen")
    val zoomLock = booleanPreferencesKey("zoom_lock")
}

internal fun Preferences.toEditorPreferences(): EditorPreferences {
    val d = EditorPreferences()
    return EditorPreferences(
        penType = enumOrDefault(this[EditorKeys.penType], d.penType),
        penLevel = this[EditorKeys.penLevel] ?: d.penLevel,
        penColor = this[EditorKeys.penColor] ?: d.penColor,
        penPressureSensitive = this[EditorKeys.penPressure] ?: d.penPressureSensitive,
        highlighterLevel = this[EditorKeys.hlLevel] ?: d.highlighterLevel,
        highlighterTip = this[EditorKeys.hlTip] ?: d.highlighterTip,
        highlighterOpacity = this[EditorKeys.hlOpacity] ?: d.highlighterOpacity,
        highlighterColor = this[EditorKeys.hlColor] ?: d.highlighterColor,
        eraserSize = this[EditorKeys.eraserSize] ?: d.eraserSize,
        eraserMode = this[EditorKeys.eraserMode] ?: d.eraserMode,
        eraserHighlighterOnly = this[EditorKeys.eraserHlOnly] ?: d.eraserHighlighterOnly,
        selectionMode = this[EditorKeys.selMode] ?: d.selectionMode,
        selectionIncludePartial = this[EditorKeys.selPartial] ?: d.selectionIncludePartial,
        recentColors = this[EditorKeys.recent].orEmpty().split(',').mapNotNull { it.toLongOrNull() },
        paletteSets = parsePaletteSets(this[EditorKeys.paletteSets]),
        favoritePens = FavoritePens.decode(this[EditorKeys.favoritePens]),
        easyPadGuideSeen = this[EditorKeys.easyPadGuideSeen] ?: d.easyPadGuideSeen,
        bodyGuideSeen = this[EditorKeys.bodyGuideSeen] ?: d.bodyGuideSeen,
        fingerDrawing = this[EditorKeys.finger] ?: d.fingerDrawing,
        stylusSeen = this[EditorKeys.stylusSeen] ?: d.stylusSeen,
        zoomLock = this[EditorKeys.zoomLock] ?: d.zoomLock,
    )
}

private fun androidx.datastore.preferences.core.MutablePreferences.write(p: EditorPreferences) {
    this[EditorKeys.penType] = p.penType.name
    this[EditorKeys.penLevel] = p.penLevel
    this[EditorKeys.penColor] = p.penColor
    this[EditorKeys.penPressure] = p.penPressureSensitive
    this[EditorKeys.hlLevel] = p.highlighterLevel
    this[EditorKeys.hlColor] = p.highlighterColor
    this[EditorKeys.hlTip] = p.highlighterTip
    this[EditorKeys.hlOpacity] = p.highlighterOpacity
    this[EditorKeys.eraserSize] = p.eraserSize
    this[EditorKeys.eraserMode] = p.eraserMode
    this[EditorKeys.eraserHlOnly] = p.eraserHighlighterOnly
    this[EditorKeys.selMode] = p.selectionMode
    this[EditorKeys.selPartial] = p.selectionIncludePartial
    this[EditorKeys.recent] = p.recentColors.take(EditorPreferences.MAX_RECENT_COLORS).joinToString(",")
    this[EditorKeys.paletteSets] = normalizePaletteSets(p.paletteSets).joinToString(",")
    this[EditorKeys.favoritePens] = FavoritePens.encode(p.favoritePens)
    this[EditorKeys.easyPadGuideSeen] = p.easyPadGuideSeen
    this[EditorKeys.bodyGuideSeen] = p.bodyGuideSeen
    this[EditorKeys.finger] = p.fingerDrawing
    this[EditorKeys.stylusSeen] = p.stylusSeen
    this[EditorKeys.zoomLock] = p.zoomLock
}

/** Valid ids only, no duplicates, at most [EditorPreferences.MAX_PALETTE_SETS]; empty falls back to the default sets. */
internal fun normalizePaletteSets(ids: List<Int>): List<Int> =
    ids.filter { it in 1..EditorPreferences.PALETTE_SET_COUNT }.distinct().take(EditorPreferences.MAX_PALETTE_SETS)
        .ifEmpty { EditorPreferences.DEFAULT_PALETTE_SETS }

internal fun parsePaletteSets(raw: String?): List<Int> =
    if (raw == null) EditorPreferences.DEFAULT_PALETTE_SETS else normalizePaletteSets(raw.split(',').mapNotNull { it.toIntOrNull() })

internal fun List<Long>.withRecent(argb: Long): List<Long> =
    (listOf(argb) + (this - argb)).take(EditorPreferences.MAX_RECENT_COLORS)

@Singleton
internal class DataStoreEditorPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : EditorPreferencesRepository {
    private val store get() = context.editorPreferencesStore

    override val preferences: Flow<EditorPreferences> = context.editorPreferencesStore.data.map { it.toEditorPreferences() }

    override suspend fun update(transform: (EditorPreferences) -> EditorPreferences) {
        store.edit { it.write(transform(it.toEditorPreferences())) }
    }

    override suspend fun addRecentColor(argb: Long) = update { it.copy(recentColors = it.recentColors.withRecent(argb)) }
}

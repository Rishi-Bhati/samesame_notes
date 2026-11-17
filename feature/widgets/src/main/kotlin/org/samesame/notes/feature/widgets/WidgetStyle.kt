package org.samesame.notes.feature.widgets

import android.content.Context
import androidx.compose.ui.graphics.Color

/** The background choices of every widget: white, black, or follow the system's dark mode. */
enum class WidgetBackground { MatchDark, White, Black }

/** Per-widget look. [opacity] is the background's opacity in percent (100 = solid), in steps of [OPACITY_STEP]. */
data class WidgetStyle(val background: WidgetBackground = WidgetBackground.MatchDark, val opacity: Int = MAX_OPACITY) {

    /**
     * Whether content (text, icons, the rendered page) is drawn dark-themed (light ink on a dark ground), for the system's
     * [systemNight] mode. See [WidgetTheme.contentDark].
     */
    fun isDark(systemNight: Boolean, wallpaperDarkText: Boolean? = null): Boolean =
        WidgetTheme.contentDark(this, systemNight, wallpaperDarkText)

    companion object {
        const val MIN_OPACITY = 0
        const val MAX_OPACITY = 100
        const val OPACITY_STEP = 10
        fun clampOpacity(value: Int) = value.coerceIn(MIN_OPACITY, MAX_OPACITY)

        /** [value] clamped and rounded to the nearest step (the slider and stored values only take whole steps). */
        fun stepOpacity(value: Float): Int =
            clampOpacity((Math.round(value / OPACITY_STEP) * OPACITY_STEP))
    }
}

/** A colour for light and for dark system mode. A fixed background uses the same colour for both. */
class RolePair(val day: Color, val night: Color) {
    fun pick(night: Boolean) = if (night) this.night else day
}

/** The colours of one widget for a [WidgetStyle]. [surface] carries the opacity; [tile] keeps a readable minimum. */
class WidgetColorRoles(
    val surface: RolePair,
    /** Inner rounded panels (buttons, list rows, page frame). */
    val tile: RolePair,
    val text: RolePair,
    val textSecondary: RolePair,
    val icon: RolePair,
    val accent: RolePair,
    /** Icon/text drawn on [accent]. */
    val onAccent: RolePair,
    /** A soft accent wash (the creator's "write" bar). */
    val accentTile: RolePair,
)

/** The widget design tokens (own palette, aligned with the app's light and dark tokens). */
object WidgetTokens {
    val surfaceWhite = Color(0xFFFFFFFF)
    val surfaceBlack = Color(0xFF000000)
    /** Match dark mode at night: the app's card tone, softer than pure black on a wallpaper. */
    val surfaceNight = Color(0xFF1A1A1A)

    val tileLight = Color(0xFFF1F1F3)
    val tileDark = Color(0xFF2B2B2B)

    val textLight = Color(0xFF1A1A1A)
    val textSecondaryLight = Color(0xFF6B6B6B)
    val iconLight = Color(0xFF3A3A3A)
    val textDark = Color(0xFFF5F5F5)
    val textSecondaryDark = Color(0xFFA6A6A6)
    val iconDark = Color(0xFFD6D6D6)

    val accentLight = Color(0xFFE5532F)
    val accentDark = Color(0xFFF46A4E)
    val onAccent = Color(0xFFFFFFFF)
}

/** Pure theme rules of the widgets (JVM-tested). */
object WidgetTheme {
    /** Below this opacity the wallpaper, not the background, decides whether the content is light or dark. */
    const val SEE_THROUGH_BELOW = 50

    /** The least alpha an inner tile keeps on a see-through widget, so its text stays readable on any wallpaper. */
    const val MIN_TILE_ALPHA = 0.6f

    /**
     * Whether the content is dark-themed. With a mostly solid background the background decides (white = light content,
     * black = dark content, match = the system mode). A mostly see-through widget sits on the wallpaper instead, so the
     * wallpaper's hint decides ([wallpaperDarkText] true = the wallpaper is light and wants dark text); unknown = background.
     */
    fun contentDark(style: WidgetStyle, systemNight: Boolean, wallpaperDarkText: Boolean?): Boolean {
        if (style.opacity < SEE_THROUGH_BELOW && wallpaperDarkText != null) return !wallpaperDarkText
        return when (style.background) {
            WidgetBackground.MatchDark -> systemNight
            WidgetBackground.White -> false
            WidgetBackground.Black -> true
        }
    }

    /** Alpha of the inner tiles: solid on a solid widget, never below [MIN_TILE_ALPHA]. */
    fun tileAlpha(opacity: Int): Float =
        MIN_TILE_ALPHA + (1f - MIN_TILE_ALPHA) * WidgetStyle.clampOpacity(opacity) / 100f

    fun surfaceColor(background: WidgetBackground, night: Boolean): Color = when (background) {
        WidgetBackground.White -> WidgetTokens.surfaceWhite
        WidgetBackground.Black -> WidgetTokens.surfaceBlack
        WidgetBackground.MatchDark -> if (night) WidgetTokens.surfaceNight else WidgetTokens.surfaceWhite
    }

    fun roles(style: WidgetStyle, wallpaperDarkText: Boolean? = null): WidgetColorRoles {
        val surfaceAlpha = WidgetStyle.clampOpacity(style.opacity) / 100f
        val tileAlpha = tileAlpha(style.opacity)
        val darkDay = contentDark(style, systemNight = false, wallpaperDarkText = wallpaperDarkText)
        val darkNight = contentDark(style, systemNight = true, wallpaperDarkText = wallpaperDarkText)
        fun pair(light: Color, dark: Color) = RolePair(if (darkDay) dark else light, if (darkNight) dark else light)
        val accent = pair(WidgetTokens.accentLight, WidgetTokens.accentDark)
        return WidgetColorRoles(
            surface = RolePair(
                surfaceColor(style.background, night = false).copy(alpha = surfaceAlpha),
                surfaceColor(style.background, night = true).copy(alpha = surfaceAlpha),
            ),
            tile = pair(WidgetTokens.tileLight.copy(alpha = tileAlpha), WidgetTokens.tileDark.copy(alpha = tileAlpha)),
            text = pair(WidgetTokens.textLight, WidgetTokens.textDark),
            textSecondary = pair(WidgetTokens.textSecondaryLight, WidgetTokens.textSecondaryDark),
            icon = pair(WidgetTokens.iconLight, WidgetTokens.iconDark),
            accent = accent,
            onAccent = RolePair(WidgetTokens.onAccent, WidgetTokens.onAccent),
            accentTile = RolePair(accent.day.copy(alpha = 0.14f), accent.night.copy(alpha = 0.18f)),
        )
    }
}

/** Where each widget's style is kept (`widget id -> background, opacity`), next to the other widget preferences. */
class WidgetStyleStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("widget_style", Context.MODE_PRIVATE)

    fun get(widgetId: Int): WidgetStyle = WidgetStyle(
        background = WidgetBackground.entries.getOrElse(prefs.getInt("bg$widgetId", 0)) { WidgetBackground.MatchDark },
        opacity = WidgetStyle.clampOpacity(prefs.getInt("op$widgetId", WidgetStyle.MAX_OPACITY)),
    )

    /** Whether the widget was configured (a style was saved for it). */
    fun has(widgetId: Int): Boolean = prefs.contains("bg$widgetId")

    fun set(widgetId: Int, style: WidgetStyle) {
        prefs.edit().putInt("bg$widgetId", style.background.ordinal).putInt("op$widgetId", WidgetStyle.clampOpacity(style.opacity)).apply()
    }

    fun clear(widgetId: Int) {
        prefs.edit().remove("bg$widgetId").remove("op$widgetId").apply()
    }
}

package org.samesame.notes.feature.widgets

import android.content.Context
import androidx.compose.ui.graphics.Color

/** The background choices of every widget: white, black, or follow the system's dark mode. */
enum class WidgetBackground { MatchDark, White, Black }

/** Per-widget look. [opacity] is the background's opacity in percent (100 = solid). */
data class WidgetStyle(val background: WidgetBackground = WidgetBackground.MatchDark, val opacity: Int = 100) {
    /** Whether content (text, icons, the rendered page) is dark-themed, given whether the system is in night mode. */
    fun isDark(systemNight: Boolean): Boolean = when (background) {
        WidgetBackground.MatchDark -> systemNight
        WidgetBackground.White -> false
        WidgetBackground.Black -> true
    }

    companion object {
        const val MIN_OPACITY = 0
        const val MAX_OPACITY = 100
        fun clampOpacity(value: Int) = value.coerceIn(MIN_OPACITY, MAX_OPACITY)
    }
}

/** A colour for light and for dark. A fixed background uses the same colour for both. */
class RolePair(val day: Color, val night: Color) {
    fun pick(dark: Boolean) = if (dark) night else day
}

/** The colours of one widget for a [WidgetStyle]: [surface] carries the opacity, the rest is solid. */
class WidgetColorRoles(
    val surface: RolePair,
    val text: RolePair,
    val textSecondary: RolePair,
    val icon: RolePair,
    val accent: RolePair,
    /** The inner rounded panel of the creator widget (the buttons sit on it). */
    val inset: RolePair,
)

object WidgetColorRules {
    private val lightText = Color(0xFF1A1A1A)
    private val lightSecondary = Color(0xFF6E6E6E)
    private val lightIcon = Color(0xFF3A3A3A)
    private val lightAccent = Color(0xFFE5532F)
    private val darkText = Color(0xFFFAFAFA)
    private val darkSecondary = Color(0xFFA9A9A9)
    private val darkIcon = Color(0xFFCCCCCC)
    private val darkAccent = Color(0xFFF46A4E)
    private val matchLightSurface = Color(0xFFFFFFFF)
    private val matchDarkSurface = Color(0xFF171717)

    fun roles(style: WidgetStyle): WidgetColorRoles {
        val alpha = WidgetStyle.clampOpacity(style.opacity) / 100f
        fun solid(c: Color) = RolePair(c, c)
        return when (style.background) {
            WidgetBackground.MatchDark -> WidgetColorRoles(
                surface = RolePair(matchLightSurface.copy(alpha = alpha), matchDarkSurface.copy(alpha = alpha)),
                text = RolePair(lightText, darkText), textSecondary = RolePair(lightSecondary, darkSecondary),
                icon = RolePair(lightIcon, darkIcon), accent = RolePair(lightAccent, darkAccent),
                inset = RolePair(Color(0xFFF2F2F2), Color(0xFF2A2A2A)),
            )
            WidgetBackground.White -> WidgetColorRoles(
                surface = solid(Color.White.copy(alpha = alpha)), text = solid(lightText), textSecondary = solid(lightSecondary),
                icon = solid(lightIcon), accent = solid(lightAccent), inset = solid(Color(0xFFF2F2F2)),
            )
            WidgetBackground.Black -> WidgetColorRoles(
                surface = solid(Color.Black.copy(alpha = alpha)), text = solid(darkText), textSecondary = solid(darkSecondary),
                icon = solid(darkIcon), accent = solid(darkAccent), inset = solid(Color(0xFF2A2A2A)),
            )
        }
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

package org.samesame.notes.feature.widgets

import androidx.glance.color.ColorProvider
import org.samesame.notes.core.designsystem.theme.SameSameDarkColors
import org.samesame.notes.core.designsystem.theme.SameSameLightColors

/** Widget colours: the app's light and dark tokens, picked by the system day/night setting ("match dark mode"). */
internal object WidgetColors {
    val surface = ColorProvider(day = SameSameLightColors.card, night = SameSameDarkColors.card)
    val text = ColorProvider(day = SameSameLightColors.textPrimary, night = SameSameDarkColors.textPrimary)
    val textSecondary = ColorProvider(day = SameSameLightColors.textSecondary, night = SameSameDarkColors.textSecondary)
    val icon = ColorProvider(day = SameSameLightColors.icon, night = SameSameDarkColors.icon)
    val accent = ColorProvider(day = SameSameLightColors.accent, night = SameSameDarkColors.accent)
    val divider = ColorProvider(day = SameSameLightColors.divider, night = SameSameDarkColors.divider)
}

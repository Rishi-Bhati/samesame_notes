package org.samesame.notes.feature.widgets

import androidx.glance.color.ColorProvider

/** [WidgetColorRoles] as Glance colour providers (day/night resolved by the system when the widget is drawn). */
internal class WidgetPalette(roles: WidgetColorRoles) {
    val surface = roles.surface.glance()
    val text = roles.text.glance()
    val textSecondary = roles.textSecondary.glance()
    val icon = roles.icon.glance()
    val accent = roles.accent.glance()

    private fun RolePair.glance() = ColorProvider(day = day, night = night)
}

internal fun widgetPalette(style: WidgetStyle) = WidgetPalette(WidgetColorRules.roles(style))

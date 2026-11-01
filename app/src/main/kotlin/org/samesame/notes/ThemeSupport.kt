package org.samesame.notes

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import org.samesame.notes.core.designsystem.theme.SameSameDarkColors
import org.samesame.notes.core.designsystem.theme.SameSameLightColors
import org.samesame.notes.core.model.ThemeMode

/** Whether the system is in dark mode right now. */
fun Context.systemDark(): Boolean =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/** The theme the app uses for [mode] while the system is [systemDark]. */
fun resolveDark(mode: ThemeMode, systemDark: Boolean): Boolean = mode.isDark(systemDark)

/**
 * Colours the window before the first Compose frame, so a forced Light/Dark choice does not flash the system theme's
 * background during launch. Also sets the status/navigation bar icon appearance.
 */
fun Activity.applyLaunchAppearance(dark: Boolean) {
    val colors = if (dark) SameSameDarkColors else SameSameLightColors
    window.setBackgroundDrawable(ColorDrawable(colors.background.toArgb()))
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
}

/** Keeps the status/navigation bar icons readable when the theme changes while the activity is running. */
@Composable
fun SystemBarAppearance(dark: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

/** The resolved dark state of the current theme choice, kept in sync with the system while [ThemeMode.System]. */
@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = resolveDark(mode, isSystemInDarkTheme())

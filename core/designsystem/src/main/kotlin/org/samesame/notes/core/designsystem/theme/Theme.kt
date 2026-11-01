package org.samesame.notes.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

val LocalSameSameColors = staticCompositionLocalOf { SameSameDarkColors }

/** Access to the One UI tokens: `SameSameTheme.colors.card`. */
object SameSameTheme {
    val colors: SameSameColors
        @Composable @ReadOnlyComposable get() = LocalSameSameColors.current
}

private fun SameSameColors.toMaterial() = if (isDark) {
    darkColorScheme(
        primary = accent, onPrimary = Color.Black, primaryContainer = elevated, onPrimaryContainer = textPrimary,
        secondary = accent, background = background, onBackground = textPrimary,
        surface = background, onSurface = textPrimary, surfaceVariant = card, onSurfaceVariant = textSecondary,
        surfaceContainer = card, surfaceContainerHigh = elevated, surfaceContainerHighest = elevated,
        surfaceContainerLow = card, surfaceContainerLowest = background,
        outline = divider, outlineVariant = divider, scrim = scrim,
    )
} else {
    lightColorScheme(
        primary = accent, onPrimary = Color.White, primaryContainer = pill, onPrimaryContainer = textPrimary,
        secondary = accent, background = background, onBackground = textPrimary,
        surface = background, onSurface = textPrimary, surfaceVariant = card, onSurfaceVariant = textSecondary,
        surfaceContainer = card, surfaceContainerHigh = elevated, surfaceContainerHighest = elevated,
        surfaceContainerLow = card, surfaceContainerLowest = background,
        outline = divider, outlineVariant = divider, scrim = scrim,
    )
}

private val SameSameShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SameSameTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) SameSameDarkColors else SameSameLightColors
    CompositionLocalProvider(LocalSameSameColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = SameSameTypography,
            shapes = SameSameShapes,
            content = content,
        )
    }
}

package org.samesame.notes.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * One UI style colour tokens. Dark values were measured from reference screenshots (Pillow pixel
 * samples); the app uses a fixed orange accent and never dynamic colour.
 */
@Immutable
class SameSameColors(
    val isDark: Boolean,
    /** Screen background. */
    val background: Color,
    /** Note cards, drawer sheet, manage-folders card. */
    val card: Color,
    /** Popup menus, FAB, selected drawer row, pill buttons' base. */
    val elevated: Color,
    /** Dialog sheet. */
    val dialog: Color,
    /** Secondary pill button (e.g. Manage folders). */
    val pill: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /** Tertiary text: drawer row counts, sort-bar label, muted hints. */
    val textTertiary: Color,
    /** Active icons (drawer rows, toolbar icons). */
    val icon: Color,
    /** De-emphasised icons: the drawer settings gear, search placeholder. */
    val iconMuted: Color,
    val accent: Color,
    val divider: Color,
    /** Unchecked selection circle outline. */
    val checkOutline: Color,
    /** "Add"/"Create" green used for the plus in Manage folders. */
    val positive: Color,
    /** Search-match background. */
    val highlight: Color,
    val scrim: Color,
)

// Dark: measured. bg #010101 (black), card #171717, menu/FAB/selected #3A3A3A, dialog #252525,
// pill #343434, text #FAFAFA / #A9A9A9, accent #F46A4E, unchecked outline #727272, plus #00A53C.
val SameSameDarkColors = SameSameColors(
    isDark = true,
    background = Color(0xFF010101),
    card = Color(0xFF171717),
    elevated = Color(0xFF3A3A3A),
    dialog = Color(0xFF252525),
    pill = Color(0xFF343434),
    textPrimary = Color(0xFFFAFAFA),
    textSecondary = Color(0xFFA9A9A9),
    textTertiary = Color(0xFF999999),
    icon = Color(0xFFCCCCCC),
    iconMuted = Color(0xFF797979),
    accent = Color(0xFFF46A4E),
    divider = Color(0xFF3A3A3A),
    checkOutline = Color(0xFF727272),
    positive = Color(0xFF00A53C),
    highlight = Color(0xFF5E3A34),
    scrim = Color(0x99000000),
)

// Light: unverified, no light reference yet. Derived from One UI light conventions (near-white
// background, white cards).
val SameSameLightColors = SameSameColors(
    isDark = false,
    background = Color(0xFFF6F6F6),
    card = Color(0xFFFFFFFF),
    elevated = Color(0xFFFFFFFF),
    dialog = Color(0xFFFAFAFA),
    pill = Color(0xFFE8E8E8),
    textPrimary = Color(0xFF1A1A1A),
    textSecondary = Color(0xFF6E6E6E),
    textTertiary = Color(0xFF767676),
    icon = Color(0xFF3A3A3A),
    iconMuted = Color(0xFF6E6E6E),
    accent = Color(0xFFE5532F),
    divider = Color(0xFFDADADA),
    checkOutline = Color(0xFF9A9A9A),
    positive = Color(0xFF00A53C),
    highlight = Color(0xFFFFD9CF),
    scrim = Color(0x66000000),
)

/** Swatches shown first in the pen color picker. */
val PenSwatches: List<Color> = listOf(
    Color(0xFF000000), Color(0xFF5F6368), Color(0xFFFFFFFF),
    Color(0xFFD93025), Color(0xFFF29900), Color(0xFFF9D71C),
    Color(0xFF188038), Color(0xFF1A73E8), Color(0xFF9334E6),
    Color(0xFFE8710A), Color(0xFF12B5CB), Color(0xFFE52592),
)

/** The 12 folder colours of the create-folder dialog; the first is the "no colour" grey. Measured. */
val FolderColors: List<Color> = listOf(
    Color(0xFF939393), Color(0xFFFF4133), Color(0xFFFF7043), Color(0xFFFFC107),
    Color(0xFF8BC34A), Color(0xFF009E7C), Color(0xFF47D6DC), Color(0xFFF6B7B4),
    Color(0xFFBB5FCB), Color(0xFFA4BDFD), Color(0xFF5485EE), Color(0xFF7E6967),
)

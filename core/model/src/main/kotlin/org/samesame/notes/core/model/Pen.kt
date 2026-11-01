package org.samesame.notes.core.model

import kotlinx.serialization.Serializable

/**
 * Pen families offered in the handwriting toolbar. [fixedAlpha] is a forced opacity (0–255) for
 * pens that are translucent by nature, or null when the user picks the opacity.
 */
@Serializable
enum class PenType(val fixedAlpha: Int? = null) {
    Fountain,
    Calligraphy,
    Pen,
    Pencil(fixedAlpha = 160),
    Brush,
    Highlighter(fixedAlpha = 115),
}

/** A pen preset: what the user picks, saves as a favorite, and what each stroke records. */
@Serializable
data class PenSettings(
    val type: PenType = PenType.Pen,
    /** ARGB. */
    val color: Long = 0xFF000000,
    /** Stroke width in page units. */
    val size: Float = 4f,
    /** Whether pressure varies the width. */
    val pressureSensitive: Boolean = true,
)

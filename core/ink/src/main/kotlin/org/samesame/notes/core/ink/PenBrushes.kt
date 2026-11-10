package org.samesame.notes.core.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.strokes.Stroke
import org.samesame.notes.core.ink.brush.PenFamilies
import org.samesame.notes.core.model.PenSettings
import org.samesame.notes.core.model.PenType

/**
 * Maps the app's pen presets onto androidx.ink brush families.
 *
 * Each family carries a stable key in [BrushFamily.developerComment]. The key survives native
 * round-trips and is what [StrokeCodec] writes to disk, so a family can be retuned later without
 * breaking saved notes.
 */
object PenBrushes {
    private const val KEY_PREFIX = "samesame/"
    private const val EPSILON = 0.1f

    private val families = HashMap<String, BrushFamily>()

    fun brushFor(pen: PenSettings): Brush {
        val color = pen.type.fixedAlpha?.let { alpha -> (pen.color and 0x00FFFFFF) or (alpha.toLong() shl 24) }
            ?: pen.color
        return Brush.createWithColorIntArgb(
            family = familyFor(pen.type, pen.pressureSensitive),
            colorIntArgb = color.toInt(),
            size = pen.size,
            epsilon = EPSILON,
        )
    }

    /** A brush of the family stored under [key] with exactly this colour (no forced opacity) and size. */
    fun brushForKey(key: String, colorIntArgb: Int, size: Float): Brush =
        Brush.createWithColorIntArgb(familyForKey(key), colorIntArgb, size, EPSILON)

    /** Recovers the preset that drew [stroke], or null for a brush this app didn't create. */
    fun penFor(stroke: Stroke): PenSettings? {
        val (type, pressure) = parseKey(stroke.brush.family.developerComment) ?: return null
        return PenSettings(
            type = type,
            color = stroke.brush.colorIntArgb.toLong() and 0xFFFFFFFF,
            size = stroke.brush.size,
            pressureSensitive = pressure,
        )
    }

    /** Key of the round marker tip ("Marker pen" tips of the highlighter panel); stored as a highlighter-kind stroke. */
    const val ROUND_MARKER_KEY = "samesame/Highlighter/round"

    /** The round-tipped marker family (the flat tip is [PenType.Highlighter]'s). */
    fun markerFamily(): BrushFamily = synchronized(families) {
        families.getOrPut(ROUND_MARKER_KEY) { PenFamilies.buildRoundMarker(ROUND_MARKER_KEY) }
    }

    /** Family for a stored key; unknown keys fall back to the pressure pen. */
    fun familyForKey(key: String): BrushFamily {
        if (key == ROUND_MARKER_KEY) return markerFamily()
        val (type, pressure) = parseKey(key) ?: (PenType.Pen to true)
        return familyFor(type, pressure)
    }

    internal fun familyFor(type: PenType, pressureSensitive: Boolean): BrushFamily = synchronized(families) {
        val key = keyOf(type, pressureSensitive)
        families.getOrPut(key) { PenFamilies.build(type, pressureSensitive, key) }
    }

    internal fun keyOf(type: PenType, pressureSensitive: Boolean) =
        "$KEY_PREFIX${type.name}/${if (pressureSensitive) "pressure" else "fixed"}"

    internal fun parseKey(key: String): Pair<PenType, Boolean>? {
        if (key == ROUND_MARKER_KEY) return PenType.Highlighter to false
        val parts = key.removePrefix(KEY_PREFIX).split('/')
        if (!key.startsWith(KEY_PREFIX) || parts.size != 2) return null
        val type = PenType.entries.firstOrNull { it.name == parts[0] } ?: return null
        return type to (parts[1] == "pressure")
    }
}

package org.samesame.notes.core.sdocx

import org.samesame.notes.core.model.PenType
import org.samesame.notes.core.sdocx.io.LeWriter

/**
 * What a new stroke of one reference pen is written with, taken from strokes the reference app wrote
 * with that pen (device fixtures; one stroke per pen, default settings).
 *
 * To add a pen, or to correct one when a new capture shows other values, change its entry in
 * [SdocxPens]; nothing else depends on the constants.
 */
class SdocxPenProfile(
    /** Last segment of the pen class name. */
    val suffix: String,
    /** The pen's advanced-setting string as stored in the note's string table. */
    val advanced: String,
    /** Style flags byte (payload +15): bit 0 second size word, bit 2 an `i32 1`, bit 5 a trailing f32. */
    private val styleFlags: Int,
    /** The trailing f32 of the style block when bit 5 is set (meaning unknown, constant per pen). */
    private val tail: Float?,
    /** First byte of the header's flag word: 0x25 for the pens, 0x65 for the highlighter. */
    private val flagByte: Int = 0x25,
) {
    val className: String get() = SdocxPens.CLASS_PREFIX + suffix

    /** The seven header bytes after the tool type: the flag word, then the style flags. */
    val header: ByteArray get() = byteArrayOf(flagByte.toByte(), 0x00, 0x04, 0x8E.toByte(), styleFlags.toByte(), 0x00, 0x00)

    /** The style block after its 16-byte head, for a stroke of [penSize]. */
    fun styleRest(penSize: Float): ByteArray = LeWriter(12).apply {
        if (styleFlags and 0x01 != 0) f32(penSize)
        if (styleFlags and 0x04 != 0) i32(1)
        if (styleFlags and 0x20 != 0) f32(tail ?: 1f)
    }.toByteArray()
}

/**
 * Pen identity mapping between `.sdocx` and SameSame.
 *
 * A stroke names its pen through the note's string table: the style block holds the id of the pen
 * class name and the id of its advanced-setting string (verified on five pens and the highlighter).
 * SameSame identifies a pen family by its `PenBrushes` key, `samesame/<PenType>/<pressure|fixed>`.
 */
object SdocxPens {

    /** Package of the reference app's built-in pen classes (a format identifier, objects spec §4.1.1). */
    const val CLASS_PREFIX = "com.samsung.android.sdk.pen.pen.preload."

    private const val KEY_PREFIX = "samesame/"
    private const val ROUND_MARKER_KEY = "samesame/Highlighter/round"

    val FOUNTAIN = SdocxPenProfile("FountainPen", "14;", 0x25, 1.5f)
    val CALLIGRAPHY = SdocxPenProfile("ObliquePen", "4;", 0x25, 1.5f)
    val PEN = SdocxPenProfile("InkPen2", "8;", 0x25, Float.fromBits(0x3EA8F5C3))
    val PENCIL = SdocxPenProfile("Pencil2", "11;", 0x05, null)
    val BRUSH = SdocxPenProfile("BrushPen", "9;", 0x25, 1f)
    val HIGHLIGHTER = SdocxPenProfile("Marker4", "7;", 0x24, 2f, flagByte = 0x65)

    /** The pen a SameSame stroke is exported as, from its `PenBrushes` key. Unknown keys are the plain pen. */
    fun profileForKey(penKey: String): SdocxPenProfile = when (penTypeOfKey(penKey)) {
        PenType.Fountain -> FOUNTAIN
        PenType.Calligraphy -> CALLIGRAPHY
        PenType.Pen -> PEN
        PenType.Pencil -> PENCIL
        PenType.Brush -> BRUSH
        PenType.Highlighter -> HIGHLIGHTER
    }

    private fun penTypeOfKey(penKey: String): PenType {
        val name = penKey.removePrefix(KEY_PREFIX).substringBefore('/')
        return PenType.entries.firstOrNull { it.name == name } ?: PenType.Pen
    }

    /** Pen class name → the closest SameSame pen family (objects spec §4.1.1 pen table, §14.5). */
    fun penTypeOf(penName: String?): PenType {
        val name = penName.orEmpty().substringAfterLast('.')
        return when {
            name.isEmpty() -> PenType.Pen
            name.endsWith("FountainPen") -> PenType.Fountain
            name.endsWith("ObliquePen") || name.endsWith("CalligraphyPen") -> PenType.Calligraphy
            name.contains("InkPen") -> PenType.Pen
            name.contains("Pencil") || name.contains("Crayon") -> PenType.Pencil
            name.contains("Marker") || name.contains("Highlighter") -> PenType.Highlighter
            name.contains("Brush") || name.contains("WaterColor") || name.contains("Oil") -> PenType.Brush
            else -> PenType.Pen
        }
    }

    /**
     * The `PenBrushes` key an imported stroke of [penName] is shown with. Highlighters have no
     * pressure response; the round-tipped one maps to the round marker family.
     */
    fun keyFor(penName: String?): String {
        val type = penTypeOf(penName)
        if (type == PenType.Highlighter) {
            return if (penName.orEmpty().endsWith("Marker3")) ROUND_MARKER_KEY else "$KEY_PREFIX${type.name}/fixed"
        }
        return "$KEY_PREFIX${type.name}/pressure"
    }

    /** True when both keys name the same pen family, whatever their pressure setting. */
    fun sameFamily(a: String, b: String): Boolean = profileForKey(a) === profileForKey(b)
}

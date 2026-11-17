package org.samesame.notes.core.richtext.edit.math

import java.math.BigDecimal
import java.math.MathContext

/** What a unit measures; quantities add up only within one dimension. */
enum class MathDimension { Length, Mass, Volume, Area, Time, Speed, Data, Temperature }

/**
 * One unit of one dimension: [factor] converts it to the dimension's base unit (metre, gram, litre, m², second,
 * m/s, byte, °C). Temperature also has an [offset], because it is affine rather than a plain factor
 * (°F → °C is `(v - 32) × 5/9`, K → °C is `v - 273.15`).
 */
class MathUnit internal constructor(
    val dimension: MathDimension,
    val factor: BigDecimal,
    /** How the unit is written when the user's own spelling is not carried over (derived units). */
    val symbol: String,
    private val offset: BigDecimal = BigDecimal.ZERO,
    private val affine: Boolean = false,
) {
    fun toBase(value: BigDecimal): BigDecimal =
        if (affine) (value - offset).multiply(factor) else value.multiply(factor)

    fun fromBase(base: BigDecimal): BigDecimal =
        if (affine) base.divide(factor, MC) + offset else base.divide(factor, MC)

    override fun toString(): String = symbol

    companion object {
        internal val MC: MathContext = MathContext(34)
    }
}

/** A unit alias found in the text, with the exact spelling the user typed (the result keeps it). */
internal class UnitMatch(val unit: MathUnit, val length: Int, val display: String)

/**
 * The unit vocabulary: short forms (case matters where it is ambiguous: `m` is metre, `M` is nothing,
 * `MB` is a megabyte and `mb` is rejected rather than guessed), long forms (case-insensitive) and the
 * compound forms `km/h`, `m/s`, `mph`, `fl oz` and `sq m`.
 */
object MathUnits {
    private class Alias(val text: String, val unit: MathUnit, val caseInsensitive: Boolean)

    private val aliases = ArrayList<Alias>()

    private fun add(dimension: MathDimension, factor: String, symbol: String, vararg names: String) {
        val unit = MathUnit(dimension, BigDecimal(factor), symbol)
        register(unit, names)
    }

    private fun addAll(unit: MathUnit, names: Array<out String>) = register(unit, names)

    private fun register(unit: MathUnit, names: Array<out String>) {
        for (name in names) {
            // Long word forms (kilometre, ounces) are case-insensitive; short forms keep their exact spelling.
            val words = name.length >= 3 && name.all { it.isLetter() } && name == name.lowercase()
            aliases += Alias(name, unit, words)
        }
    }

    private val sortedAliases: List<Alias> by lazy { aliases.sortedByDescending { it.text.length } }

    init {
        // Length (base metre)
        add(MathDimension.Length, "0.001", "mm", "mm", "millimetre", "millimetres", "millimeter", "millimeters")
        add(MathDimension.Length, "0.01", "cm", "cm", "centimetre", "centimetres", "centimeter", "centimeters")
        add(MathDimension.Length, "1", "m", "m", "metre", "metres", "meter", "meters")
        add(MathDimension.Length, "1000", "km", "km", "kilometre", "kilometres", "kilometer", "kilometers")
        add(MathDimension.Length, "0.0254", "in", "in", "inch", "inches")
        add(MathDimension.Length, "0.3048", "ft", "ft", "foot", "feet")
        add(MathDimension.Length, "0.9144", "yd", "yd", "yard", "yards")
        add(MathDimension.Length, "1609.344", "mi", "mi", "mile", "miles")

        // Mass (base gram)
        add(MathDimension.Mass, "0.001", "mg", "mg", "milligram", "milligrams")
        add(MathDimension.Mass, "1", "g", "g", "gram", "grams")
        add(MathDimension.Mass, "1000", "kg", "kg", "kilogram", "kilograms")
        add(MathDimension.Mass, "1000000", "t", "t", "tonne", "tonnes")
        add(MathDimension.Mass, "28.349523125", "oz", "oz", "ounce", "ounces")
        add(MathDimension.Mass, "453.59237", "lb", "lb", "lbs", "pound", "pounds")

        // Volume (base litre)
        add(MathDimension.Volume, "0.001", "ml", "ml", "millilitre", "millilitres", "milliliter", "milliliters")
        add(MathDimension.Volume, "0.01", "cl", "cl", "centilitre", "centilitres", "centiliter", "centiliters")
        add(MathDimension.Volume, "1", "l", "l", "L", "litre", "litres", "liter", "liters")
        add(MathDimension.Volume, "0.00492892159375", "tsp", "tsp", "teaspoon", "teaspoons")
        add(MathDimension.Volume, "0.01478676478125", "tbsp", "tbsp", "tablespoon", "tablespoons")
        add(MathDimension.Volume, "0.2365882365", "cup", "cup", "cups")
        add(MathDimension.Volume, "0.0295735295625", "fl oz", "fl oz", "floz", "fluid ounce", "fluid ounces")
        add(MathDimension.Volume, "3.785411784", "gal", "gal", "gallon", "gallons")

        // Area (base m²)
        add(MathDimension.Area, "1", "m²", "m²", "m2", "sq m", "square metre", "square metres", "square meter", "square meters")
        add(MathDimension.Area, "0.0001", "cm²", "cm²", "cm2", "sq cm", "square centimetre", "square centimetres", "square centimeter", "square centimeters")
        add(MathDimension.Area, "1000000", "km²", "km²", "km2", "sq km", "square kilometre", "square kilometres", "square kilometer", "square kilometers")
        add(MathDimension.Area, "10000", "ha", "ha", "hectare", "hectares")
        add(MathDimension.Area, "4046.8564224", "acre", "acre", "acres")
        add(MathDimension.Area, "0.09290304", "ft²", "ft²", "ft2", "sq ft", "square foot", "square feet")

        // Time (base second)
        add(MathDimension.Time, "0.001", "ms", "ms", "millisecond", "milliseconds")
        add(MathDimension.Time, "1", "s", "s", "sec", "secs", "second", "seconds")
        add(MathDimension.Time, "60", "min", "min", "mins", "minute", "minutes")
        add(MathDimension.Time, "3600", "h", "h", "hr", "hrs", "hour", "hours")
        add(MathDimension.Time, "86400", "d", "d", "day", "days")
        add(MathDimension.Time, "604800", "week", "week", "weeks")

        // Speed (base m/s)
        add(MathDimension.Speed, ratio("1000", "3600").toPlainString(), "km/h", "km/h", "kph", "kmh")
        add(MathDimension.Speed, "1", "m/s", "m/s")
        add(MathDimension.Speed, "0.44704", "mph", "mph")

        // Data (base byte); only the unambiguous capitalised forms are data units.
        add(MathDimension.Data, "1", "B", "B", "byte", "bytes")
        add(MathDimension.Data, "1000", "KB", "KB", "kb", "kilobyte", "kilobytes")
        add(MathDimension.Data, "1000000", "MB", "MB", "mb", "megabyte", "megabytes")
        add(MathDimension.Data, "1000000000", "GB", "GB", "gb", "gigabyte", "gigabytes")
        add(MathDimension.Data, "1000000000000", "TB", "TB", "tb", "terabyte", "terabytes")
        add(MathDimension.Data, "1024", "KiB", "KiB", "kib")
        add(MathDimension.Data, "1048576", "MiB", "MiB", "mib")
        add(MathDimension.Data, "1073741824", "GiB", "GiB", "gib")

        // Temperature (base °C, affine)
        addAll(MathUnit(MathDimension.Temperature, BigDecimal.ONE, "°C"), arrayOf("°C", "C", "celsius", "Celsius"))
        addAll(
            MathUnit(MathDimension.Temperature, ratio("5", "9"), "°F", BigDecimal("32"), affine = true),
            arrayOf("°F", "F", "fahrenheit", "Fahrenheit"),
        )
        addAll(
            MathUnit(MathDimension.Temperature, BigDecimal.ONE, "K", BigDecimal("273.15"), affine = true),
            arrayOf("K", "kelvin", "Kelvin"),
        )
    }

    private fun ratio(numerator: String, denominator: String): BigDecimal =
        BigDecimal(numerator).divide(BigDecimal(denominator), MathContext(34))

    /**
     * The longest known unit written at [index] of [text], or null. Units never contain digits and must end at a
     * non-letter, so `mins` does not silently become `min` + `s` and `5 km/2` still reads the `km`.
     */
    internal fun matchAt(text: String, index: Int): UnitMatch? {
        for (alias in sortedAliases) {
            val end = index + alias.text.length
            if (end > text.length) continue
            val matches = if (alias.caseInsensitive) {
                text.regionMatches(index, alias.text, 0, alias.text.length, ignoreCase = true)
            } else {
                text.regionMatches(index, alias.text, 0, alias.text.length)
            }
            if (!matches) continue
            if (end < text.length) {
                val next = text[end]
                // A digit may follow a unit directly ("1h30min"): the longest alias wins, so "m2" stays an area,
                // and a following number without a unit of its own fails the parse anyway.
                if (next.isLetter() || next == '²' || next == '³' || next == '°') continue
            }
            return UnitMatch(alias.unit, alias.text.length, text.substring(index, end))
        }
        return null
    }

    /** A unit alone (`m`, `km/h`, `fl oz`), with nothing else; used for the target of a conversion. */
    fun parseUnit(text: String): Pair<MathUnit, String>? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val match = matchAt(t, 0) ?: return null
        if (match.length != t.length) return null
        return match.unit to match.display
    }
}

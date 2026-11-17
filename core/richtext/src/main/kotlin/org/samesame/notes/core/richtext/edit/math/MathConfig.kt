package org.samesame.notes.core.richtext.edit.math

import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * How numbers are written on this device: which character is the decimal separator and which groups thousands.
 * The calculator reads both conventions (a "." decimal on a comma locale still works), but its own decimal
 * separator wins when a single separator could be either.
 */
data class MathConfig(
    val decimalSeparator: Char = '.',
    val groupingSeparator: Char = ',',
) {
    companion object {
        @Volatile private var cachedLocale: Locale? = null
        @Volatile private var cached: MathConfig? = null

        /** The device locale's separators; falls back to "." and "," for locales that only group with a space. */
        fun default(): MathConfig {
            val locale = Locale.getDefault()
            val hit = cached
            if (hit != null && cachedLocale == locale) return hit
            val symbols = DecimalFormatSymbols.getInstance(locale)
            val decimal = symbols.decimalSeparator
            val grouping = symbols.groupingSeparator
            val config = MathConfig(
                decimalSeparator = decimal,
                groupingSeparator = if (grouping == decimal || grouping.isWhitespace()) ',' else grouping,
            )
            cachedLocale = locale
            cached = config
            return config
        }

        /** For tests and for the corpus, so the result does not depend on the machine's locale. */
        val English = MathConfig('.', ',')

        /** A comma-decimal locale (1.234,56). */
        val CommaDecimal = MathConfig(',', '.')
    }
}

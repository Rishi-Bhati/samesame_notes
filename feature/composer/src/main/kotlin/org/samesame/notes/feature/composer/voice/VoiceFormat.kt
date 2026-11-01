package org.samesame.notes.feature.composer.voice

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pure naming and formatting rules of the voice chrome (JVM-tested). */
object VoiceFormat {
    private val defaultName = Regex("^Voice (\\d{3,})$")

    /** "Voice 001" for [number] (3-digit counter, wider numbers keep all their digits). */
    fun defaultName(number: Int): String = "Voice %03d".format(number)

    /** The default name of a new recording: the lowest `Voice NNN` number no recording of the note uses yet. */
    fun nextName(existing: Collection<String>): String {
        val used = existing.mapNotNullTo(HashSet()) { defaultName.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
        var n = 1
        while (n in used) n++
        return defaultName(n)
    }

    /** `mm:ss` (minutes keep counting past 59): the menu clock, the recording timer. */
    fun clock(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    /** `HH:MM:SS`, the zero-padded duration of a list row. */
    fun listDuration(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    /** The system skeleton the row date is built from (12 or 24 hour); the caller turns it into a pattern. */
    fun dateSkeleton(use24Hour: Boolean): String = if (use24Hour) "MM.dd HH:mm" else "MM.dd hh:mm"

    /** Formats [createdAtMs] with the locale-resolved [pattern] (from `DateFormat.getBestDateTimePattern`). */
    fun listDate(createdAtMs: Long, pattern: String, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat(pattern, locale).format(Date(createdAtMs))

    /** The rename dialog's confirm button is enabled only for a changed, non-blank name. */
    fun canRename(initial: String, edited: String): Boolean = edited.isNotBlank() && edited != initial
}

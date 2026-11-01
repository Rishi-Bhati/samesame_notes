package org.samesame.notes.feature.composer.voice

/**
 * The cumulative timeline of a note's recordings in list order (the reference's seek bar spans all of them). Pure position
 * arithmetic, no player. A duration of 0 or less means "unknown" and counts as 0 ms until the caller learns it.
 *
 * Rules, from the menu presenter:
 * the total is the sum of the durations; a cumulative position maps to the FIRST recording whose closed range
 * `[start, start + duration]` contains it, so a position exactly on a boundary belongs to the end of the earlier one.
 */
class VoicePlaylist(durationsMs: List<Long>) {
    private val durations: List<Long> = durationsMs.map { it.coerceAtLeast(0L) }
    private val starts: List<Long> = buildList {
        var sum = 0L
        for (d in durations) { add(sum); sum += d }
    }

    /** A place on the timeline: the recording [index] and the [offsetMs] into it. */
    data class Spot(val index: Int, val offsetMs: Long)

    val count: Int get() = durations.size
    val isEmpty: Boolean get() = durations.isEmpty()
    val totalMs: Long = durations.sum()

    fun durationOf(index: Int): Long = durations.getOrElse(index) { 0L }

    /** Cumulative position where recording [index] begins (0 for an unknown index). */
    fun startOf(index: Int): Long = starts.getOrElse(index) { 0L }

    /** Cumulative position of [offsetMs] into recording [index], the offset capped at that recording's duration when known. */
    fun positionOf(index: Int, offsetMs: Long): Long {
        if (index !in durations.indices) return 0L
        val d = durations[index]
        val o = offsetMs.coerceAtLeast(0L)
        return starts[index] + if (d > 0L) o.coerceAtMost(d) else o
    }

    fun clamp(positionMs: Long): Long = positionMs.coerceIn(0L, totalMs)

    /** Where [positionMs] (clamped to 0..total) falls; null for an empty list. */
    fun locate(positionMs: Long): Spot? {
        if (durations.isEmpty()) return null
        val p = clamp(positionMs)
        for (i in durations.indices) {
            if (p >= starts[i] && p <= starts[i] + durations[i]) return Spot(i, p - starts[i])
        }
        return Spot(durations.lastIndex, durations.last()) // unreachable: p <= total
    }

    companion object {
        /** the reference's duration rule: the elapsed time when positive, else the parsed display text, else unknown (0). */
        fun durationOf(elapsedMs: Long, displaySeconds: Long): Long =
            if (elapsedMs > 0L) elapsedMs else (displaySeconds * 1000L).coerceAtLeast(0L)
    }
}

package org.samesame.notes.core.model

/** Playback speed button of the voice player: each tap goes to the next speed, 1.0x -> 1.25x -> 1.5x -> 2.0x -> 0.5x -> 0.75x -> 1.0x. */
object VoiceSpeed {
    const val NORMAL = 1.0f

    /** The speed after [current] (the reference: 2.0 wraps to 0.5, from 1.5 up the step is 0.5, otherwise 0.25). */
    fun next(current: Float): Float = when {
        current >= 2.0f -> 0.5f
        current >= 1.5f -> current + 0.5f
        else -> current + 0.25f
    }

    /** "1.0x", "1.25x", "0.5x". */
    fun label(speed: Float): String {
        val text = "%.2f".format(java.util.Locale.ROOT, speed).trimEnd('0')
        return (if (text.endsWith(".")) text + "0" else text) + "x"
    }
}

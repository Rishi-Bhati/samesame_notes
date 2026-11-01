package org.samesame.notes.feature.composer.canvas

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.samesame.notes.core.designsystem.motion.SameSameMotion

/**
 * Pull past the end of the last page to add one (Pages mode; the reference's composer "Drag for new page." /
 * "Release to add page." with its add-page animation). Pure math: the finger's distance past the end goes through a
 * rubber band (slope [RESISTANCE] at the start, never beyond the maximum), and the shown distance commits at the threshold.
 */
object PullToAddPageMath {
    const val RESISTANCE = 0.7f
    const val THRESHOLD_DP = 88f
    const val MAX_DP = 240f

    /** Shown pull for a finger [raw] px past the end, approaching [max] px. */
    fun visible(raw: Float, max: Float): Float =
        if (raw <= 0f || max <= 0f) 0f else max * (1f - 1f / (raw * RESISTANCE / max + 1f))

    /** The finger distance that shows [visible] px (inverse of [visible]). */
    fun rawFor(visible: Float, max: Float): Float {
        if (visible <= 0f || max <= 0f) return 0f
        val v = visible.coerceAtMost(max * 0.999f)
        return max / RESISTANCE * (1f / (1f - v / max) - 1f)
    }

    fun progress(visible: Float, threshold: Float): Float = if (threshold <= 0f) 0f else (visible / threshold).coerceIn(0f, 1f)

    fun commits(visible: Float, threshold: Float): Boolean = threshold > 0f && visible >= threshold

    /**
     * Splits one pan step along the scroll axis ([along] px; negative = towards the end) while a pull of [raw] px is shown:
     * returns (pull change, rest for the scroll). A pull is undone first when the finger comes back.
     */
    fun takeBack(along: Float, raw: Float): Pair<Float, Float> =
        if (raw > 0f && along > 0f) {
            val take = minOf(along, raw)
            -take to along - take
        } else 0f to along
}

/** Live state of the pull (owned by the screen, driven by the canvas input, drawn by [PullToAddPageIndicator]). */
@Stable
class PullToAddPageState {
    /** Finger distance past the end (px). */
    var raw by mutableFloatStateOf(0f)
        private set
    var released by mutableStateOf(false)
        private set
    private val anim = Animatable(0f)

    var maxPx = 0f
    var thresholdPx = 0f
    /** Height (width, horizontal) of the canvas: a committed pull rises to it. */
    var fullPx = 0f

    /** Adds the page (one undo step) and scrolls to it; set by the screen. */
    var onCommit: suspend () -> Unit = {}

    /** Shown distance (px); read it in draw / layout phases. */
    val visible: Float get() = if (released) anim.value else PullToAddPageMath.visible(raw, maxPx)

    val armed: Boolean get() = !released && PullToAddPageMath.commits(visible, thresholdPx)

    fun dragBy(deltaPx: Float) {
        if (released) return
        raw = (raw + deltaPx).coerceAtLeast(0f)
    }

    /** Finger up (or the gesture became a pinch): commit past the threshold, otherwise spring back. */
    suspend fun release(allowCommit: Boolean = true) {
        if (released || raw <= 0f) {
            raw = 0f
            return
        }
        val shown = visible
        val commit = allowCommit && PullToAddPageMath.commits(shown, thresholdPx)
        released = true
        anim.snapTo(shown)
        try {
            if (commit) {
                // The new page rises over the viewport, then the canvas jumps to the real page under it.
                anim.animateTo(fullPx.coerceAtLeast(shown), tween(COMMIT_MS, easing = SameSameMotion.SineInOut70))
                onCommit()
            } else {
                anim.animateTo(0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow))
            }
        } finally {
            anim.snapTo(0f)
            raw = 0f
            released = false
        }
    }

    private companion object {
        const val COMMIT_MS = 250
    }
}

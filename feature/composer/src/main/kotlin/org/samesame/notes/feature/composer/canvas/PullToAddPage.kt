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
     * Opacity of the "+" and label while a committed release rises from [from] (the released distance) to [full] (the
     * viewport covered): 1 at the start, 0 once the new page fills the view, so the sheet ends as a plain page colour.
     */
    fun labelAlpha(visible: Float, from: Float, full: Float): Float =
        if (full <= from) 0f else (1f - (visible - from) / (full - from)).coerceIn(0f, 1f)

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
    /** A release past the threshold is playing out: the sheet rises, the page is added underneath, then it fades. */
    var committing by mutableStateOf(false)
        private set
    private val anim = Animatable(0f)
    private val fade = Animatable(1f)
    private var commitFrom = 0f

    var maxPx = 0f
    var thresholdPx = 0f
    /** Height (width, horizontal) of the canvas: a committed pull rises to it. */
    var fullPx = 0f

    /** Adds the page (one undo step) and scrolls to it; set by the screen. */
    var onCommit: suspend () -> Unit = {}

    /** Shown distance (px); read it in draw / layout phases. */
    val visible: Float get() = if (released) anim.value else PullToAddPageMath.visible(raw, maxPx)

    /** Opacity of the whole sheet: stays 1 while dragging and rising, then fades out over the settled page. */
    val alpha: Float get() = fade.value

    /**
     * Opacity of the "+" and the label: fades out while the sheet rises to cover the viewport, so the last frames are
     * a plain page colour that can cross-fade into the real page without a frozen title/flash.
     */
    val labelAlpha: Float get() =
        if (committing) PullToAddPageMath.labelAlpha(anim.value, commitFrom, fullPx) else 1f

    /** Keeps "Release to add page." through the whole commit rise; only untitled/idle pulls read the drag text. */
    val armed: Boolean get() =
        if (released) committing else PullToAddPageMath.commits(visible, thresholdPx)

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
                committing = true
                commitFrom = shown
                // One continuous rise: the new page sheet covers the viewport (its label fades out on the way).
                anim.animateTo(fullPx.coerceAtLeast(shown), tween(COMMIT_MS, easing = SameSameMotion.SineInOut70))
                // The page is inserted and the viewport moved while the sheet hides everything; then cross-fade the
                // sheet out over the page that is already settled in view, so there is no jump, flash or frozen frame.
                onCommit()
                fade.animateTo(0f, tween(FADE_MS, easing = SameSameMotion.SineInOut90))
            } else {
                anim.animateTo(0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow))
            }
        } finally {
            anim.snapTo(0f)
            fade.snapTo(1f)
            raw = 0f
            released = false
            committing = false
            commitFrom = 0f
        }
    }

    private companion object {
        const val COMMIT_MS = 250
        const val FADE_MS = 110
    }
}

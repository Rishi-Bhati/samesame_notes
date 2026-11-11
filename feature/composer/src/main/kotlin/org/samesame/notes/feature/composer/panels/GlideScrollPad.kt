package org.samesame.notes.feature.composer.panels

/**
 * Press-and-glide on the font-size button: how the size list follows the glide. The highlight itself travels by rate
 * ([GlideJoystick]); the list is scrolled every frame so the (fractional) highlighted size row stays centred, which
 * makes the sizes slide smoothly under a still highlight band. Pure (px), JVM-testable.
 */
internal object GlideScrollPad {
    /**
     * Scroll value that centres (fractional) row [listIndex] (rows of [rowHeight] stacked from the content top) in a
     * viewport of [viewport] px, clamped to 0..[maxScroll]; the highlighted row is then always fully visible.
     */
    fun centredScroll(listIndex: Float, rowHeight: Float, viewport: Float, maxScroll: Float): Float =
        (listIndex * rowHeight + rowHeight / 2f - viewport / 2f).coerceIn(0f, maxScroll.coerceAtLeast(0f))
}

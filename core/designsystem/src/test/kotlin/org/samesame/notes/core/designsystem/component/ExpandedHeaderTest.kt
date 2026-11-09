package org.samesame.notes.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExpandedHeaderTest {
    @Test fun `tall portrait keeps the reference height`() {
        assertThat(expandedHeaderDp(887f, landscape = false)).isWithin(0.05f).of(359.25f)
        assertThat(expandedHeaderDp(1000f, landscape = false)).isWithin(0.01f).of(359.25f)
    }

    @Test fun `short portrait window scales down`() {
        assertThat(expandedHeaderDp(700f, landscape = false)).isWithin(0.01f).of(283.5f)
        assertThat(expandedHeaderDp(500f, landscape = false)).isEqualTo(0f)
    }

    @Test fun `phone landscape is toolbar only`() {
        assertThat(expandedHeaderDp(411f, landscape = true)).isEqualTo(0f)
        assertThat(expandedHeaderDp(639f, landscape = true)).isEqualTo(0f)
    }

    @Test fun `larger landscape windows use the size class proportions`() {
        assertThat(expandedHeaderDp(640f, landscape = true)).isWithin(0.01f).of(256f)
        assertThat(expandedHeaderDp(800f, landscape = true)).isWithin(0.01f).of(304f)
        assertThat(expandedHeaderDp(1200f, landscape = true)).isWithin(0.01f).of(300f)
    }
}

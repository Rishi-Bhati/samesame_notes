package org.samesame.notes.core.richtext

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TemplateLineAlignmentTest {

    @Test fun `a line as tall as the rule spacing takes one rule`() {
        assertThat(TemplateLineAlignment.lineHeight(natural = 40f, pitch = 56f)).isEqualTo(56f)
    }

    @Test fun `a taller line snaps up to whole rules`() {
        assertThat(TemplateLineAlignment.lineHeight(natural = 59.5f, pitch = 56f)).isEqualTo(112f)
        assertThat(TemplateLineAlignment.lineHeight(natural = 120f, pitch = 56f)).isEqualTo(168f)
        assertThat(TemplateLineAlignment.lineHeight(natural = 56f, pitch = 56f)).isEqualTo(56f)
    }

    @Test fun `no pitch leaves the natural line height`() {
        assertThat(TemplateLineAlignment.lineHeight(natural = 59.5f, pitch = 0f)).isEqualTo(59.5f)
    }

    @Test fun `the first baseline is moved onto the first rule`() {
        // Baseline at 73 in page space (32 top margin + 41 ascent) -> moved up by 17 to the rule at 56.
        assertThat(
            TemplateLineAlignment.pageShift(firstRule = 56f, pageTop = 0f, contentTop = 32f, columnTop = 0f, firstBaseline = 41f),
        ).isEqualTo(-17f)
    }

    @Test fun `every page shifts by the same amount`() {
        val first = TemplateLineAlignment.pageShift(firstRule = 56f, pageTop = 0f, contentTop = 32f, columnTop = 0f, firstBaseline = 41f)
        // Page 2, first line 20 (column top 1100, baseline 1141): content top is page top + 32.
        val pageTop = 1553f
        val second = TemplateLineAlignment.pageShift(
            firstRule = 56f, pageTop = pageTop, contentTop = pageTop + 32f, columnTop = 1100f, firstBaseline = 1141f,
        )
        assertThat(second).isEqualTo(first)
    }

    @Test fun `grid and dot templates have a pitch too`() {
        assertThat(TemplateLineAlignment.lineHeight(50f, 36f)).isEqualTo(72f)
    }
}

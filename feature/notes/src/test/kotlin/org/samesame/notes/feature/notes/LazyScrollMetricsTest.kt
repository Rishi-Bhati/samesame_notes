package org.samesame.notes.feature.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LazyScrollMetricsTest {
    @Test fun `empty grid needs no bar`() {
        val metrics = lazyScrollMetrics(0, 0, 0, 0, 0, 800f)
        assertThat(metrics.thumbFraction).isEqualTo(1f)
        assertThat(metrics.scrollFraction).isEqualTo(0f)
    }

    @Test fun `all items visible fills the thumb`() {
        // 4 items of 200 on an 800 tall viewport: start 0, end 800.
        val metrics = lazyScrollMetrics(4, 0, 0, 3, 800, 800f)
        assertThat(metrics.thumbFraction).isWithin(0.0001f).of(1f)
        assertThat(metrics.scrollFraction).isEqualTo(0f)
    }

    @Test fun `half way through the list is half way down the bar`() {
        // 10 items, ~200 each, content 2000, viewport 800, first index 3 at 0 => 600 of 1200 travel.
        val metrics = lazyScrollMetrics(10, 3, 0, 6, 800, 800f)
        assertThat(metrics.thumbFraction).isWithin(0.0001f).of(0.4f)
        assertThat(metrics.scrollFraction).isWithin(0.0001f).of(0.5f)
    }

    @Test fun `unseen tail is estimated from average item extent`() {
        // 20 items, first 4 visible at 100 each => per item 100, content ~2000, offset 0.
        val metrics = lazyScrollMetrics(20, 0, 0, 3, 400, 800f)
        assertThat(metrics.itemExtent).isWithin(0.0001f).of(100f)
        assertThat(metrics.contentExtent).isWithin(0.0001f).of(2000f)
        assertThat(metrics.thumbFraction).isWithin(0.0001f).of(0.4f)
    }

    @Test fun `supplied stable extent is used even when the last item is visible`() {
        val metrics = lazyScrollMetrics(10, 6, 0, 9, 800, 800f, estimatedItemExtent = 100f)
        assertThat(metrics.contentExtent).isWithin(0.0001f).of(1000f)
        assertThat(metrics.thumbFraction).isWithin(0.0001f).of(0.8f)
    }

    @Test fun `thumb length does not change while scrolling the same item count`() {
        val extent = measuredItemExtent(0, 0, 3, 400)
        val top = lazyScrollMetrics(20, 0, 0, 3, 400, 800f, estimatedItemExtent = extent)
        val middle = lazyScrollMetrics(20, 8, -20, 10, 380, 800f, estimatedItemExtent = extent)
        assertThat(middle.thumbFraction).isEqualTo(top.thumbFraction)
        assertThat(middle.contentExtent).isEqualTo(top.contentExtent)
        assertThat(middle.scrollFraction).isGreaterThan(top.scrollFraction)
    }

    @Test fun `measured extent averages the laid out items`() {
        assertThat(measuredItemExtent(4, -50, 7, 550)).isWithin(0.0001f).of(150f)
        assertThat(measuredItemExtent(2, 0, 2, 0)).isWithin(0.0001f).of(1f)
    }

    @Test fun `stable estimate is frozen while the item count is unchanged`() {
        val first = stableScrollEstimate(ScrollEstimate(), itemCount = 40, measuredItemExtent = 100f)
        assertThat(first).isEqualTo(ScrollEstimate(40, 100f))
        val afterScroll = stableScrollEstimate(first, itemCount = 40, measuredItemExtent = 240f)
        assertThat(afterScroll).isEqualTo(first)
        val afterDeletion = stableScrollEstimate(afterScroll, itemCount = 39, measuredItemExtent = 105f)
        assertThat(afterDeletion).isEqualTo(ScrollEstimate(39, 105f))
    }

    @Test fun `holder keeps its estimate across calls`() {
        val holder = ScrollEstimateHolder()
        assertThat(holder.estimateFor(40, 100f)).isEqualTo(100f)
        assertThat(holder.estimateFor(40, 240f)).isEqualTo(100f)
        assertThat(holder.estimateFor(39, 105f)).isEqualTo(105f)
    }

    @Test fun `end of the list puts the thumb at the bottom`() {
        // Even when the frozen estimate is a little off, the last item is the end of the travel.
        val metrics = lazyScrollMetrics(20, 15, -100, 19, 700, 800f, estimatedItemExtent = 100f)
        assertThat(metrics.scrollFraction).isEqualTo(1f)
    }

    @Test fun `negative first offset still gives the top fraction`() {
        val metrics = lazyScrollMetrics(10, 0, -50, 3, 750, 800f)
        assertThat(metrics.scrollFraction).isWithin(0.0001f).of(0f)
    }

    @Test fun `scrub target maps the fraction onto an item and offset`() {
        val metrics = LazyScrollMetrics(scrollFraction = 0f, thumbFraction = 0.4f, itemExtent = 100f, contentExtent = 2000f)
        assertThat(lazyScrollTarget(metrics, 0f)).isEqualTo(0 to 0)
        assertThat(lazyScrollTarget(metrics, 0.5f)).isEqualTo(6 to 0)
        assertThat(lazyScrollTarget(metrics, 1f)).isEqualTo(12 to 0)
    }

    @Test fun `scrub target tolerates degenerate metrics`() {
        assertThat(lazyScrollTarget(LazyScrollMetrics(), 0.5f)).isEqualTo(0 to 0)
    }
}

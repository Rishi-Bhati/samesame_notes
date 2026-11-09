package org.samesame.notes.feature.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class LazyScrollMetricsTest {
    private val columns = 2

    /** Laid-out rows starting at [firstTop], each advance from [advances]. */
    private fun rowsOf(vararg advances: Float, firstTop: Int = 0): List<GridRow> {
        var top = firstTop
        return advances.mapIndexed { row, advance ->
            GridRow(index = row, top = top, advance = advance).also { top += advance.toInt() }
        }
    }

    /** The rows a real grid would report for a content [scroll] px of read-ahead [advances]. */
    private fun visibleAt(advances: List<Float>, scroll: Float, viewport: Float): List<GridRow> {
        val rows = mutableListOf<GridRow>()
        var top = 0f
        advances.forEachIndexed { row, advance ->
            val relative = top - scroll
            if (relative < viewport && relative + advance > 0f) {
                rows += GridRow(row, relative.toInt(), advance)
            }
            top += advance
        }
        return rows
    }

    /** Content y of the top of each row. */
    private fun rowStarts(advances: List<Float>): List<Float> {
        var start = 0f
        return advances.map { advance -> start.also { start += advance } }
    }

    private val variableRowHeights = listOf(300f, 900f, 400f, 1200f, 550f, 700f)
    private val variableEstimate = variableRowHeights.average().toFloat()

    private val headerAdvance = 31f

    /** Row advances of a list with one short header row followed by note rows. */
    private fun withHeader(vararg notes: Float) = listOf(headerAdvance) + notes.toList()

    private fun metricsAt(advances: List<Float>, scroll: Float, viewport: Float, headers: Int = 0, estimate: Float, known: Map<Int, Float> = emptyMap()): LazyScrollMetrics {
        val rows = visibleAt(advances, scroll, viewport)
        val items = headers + (advances.size - headers) * columns
        return lazyScrollMetrics(items, columns, headers, rows, viewport, estimate, known)
    }

    @Test fun `empty grid needs no bar`() {
        val metrics = lazyScrollMetrics(0, columns, 0, emptyList(), 800f)
        assertThat(metrics.thumbFraction).isEqualTo(1f)
        assertThat(metrics.scrollFraction).isEqualTo(0f)
        assertThat(metrics.rows).isEqualTo(0)
    }

    @Test fun `grid that fits fills the thumb`() {
        val metrics = lazyScrollMetrics(4, columns, 0, rowsOf(400f, 400f), 800f)
        assertThat(metrics.rows).isEqualTo(2)
        assertThat(metrics.thumbFraction).isEqualTo(1f)
        assertThat(metrics.scrollFraction).isEqualTo(0f)
    }

    @Test fun `position is the scrolled pixels over the scrollable pixels`() {
        // 20 notes = 10 rows; rows 0 and 1 are laid out (300, 700), the rest use the 500 px estimate.
        val metrics = lazyScrollMetrics(20, columns, 0, rowsOf(300f, 700f, firstTop = -150), 800f, estimatedRowAdvance = 500f)
        assertThat(metrics.firstRow).isEqualTo(0)
        assertThat(metrics.rowOffset).isWithin(0.001f).of(150f)
        val content = 300f + 700f + 8 * 500f
        assertThat(metrics.contentExtent).isWithin(0.01f).of(content)
        assertThat(metrics.scrollFraction).isWithin(0.0001f).of(150f / (content - 800f))
        assertThat(metrics.thumbFraction).isWithin(0.0001f).of(800f / content)
    }

    @Test fun `scrolling down increases the fraction`() {
        val top = lazyScrollMetrics(20, columns, 0, rowsOf(300f, 700f), 800f, 500f)
        val down = lazyScrollMetrics(20, columns, 0, rowsOf(300f, 700f, firstTop = -100), 800f, 500f)
        assertThat(down.scrollFraction).isGreaterThan(top.scrollFraction)
    }

    @Test fun `short header row moves the thumb in proportion to its pixels`() {
        // 1 header (31 px) + 13 notes in 2 columns = 7 note rows of 500 px.
        val advances = withHeader(500f, 500f, 500f, 500f, 500f, 500f, 500f)
        val items = 1 + 13
        val viewport = 1000f
        val known = mapOf(0 to headerAdvance) // the header was seen on the way down
        val atTop = lazyScrollMetrics(items, columns, 1, visibleAt(advances, 0f, viewport), viewport, 500f, known)
        val pastHeader = lazyScrollMetrics(items, columns, 1, visibleAt(advances, 31f, viewport), viewport, 500f, known)
        val travel = atTop.contentExtent - viewport
        // The header's 31 px is 31/travel of the bar, not a whole row unit.
        assertThat(pastHeader.scrollFraction - atTop.scrollFraction).isWithin(0.0005f).of(31f / travel)
        assertThat(pastHeader.scrollFraction - atTop.scrollFraction).isLessThan(0.02f)
    }

    @Test fun `fraction is continuous and monotonic scrolling up and down through a header`() {
        val advances = withHeader(500f, 520f, 480f, 500f, 510f, 500f, 490f)
        val items = 1 + 13
        val viewport = 1000f
        val maxScroll = advances.sum() - viewport
        val travel = maxScroll
        val known = HashMap<Int, Float>()
        // Scroll down first so the rows are measured, then back up.
        var scroll = 0f
        while (scroll <= maxScroll) {
            val rows = visibleAt(advances, scroll, viewport)
            for (i in 0 until rows.size - 1) known[rows[i].index] = rows[i].advance
            scroll += 9f
        }
        var previous = 2f
        scroll = maxScroll
        while (scroll >= 0f) {
            val m = lazyScrollMetrics(items, columns, 1, visibleAt(advances, scroll, viewport), viewport, 500f, known)
            assertThat(m.scrollFraction).isAtMost(previous)
            // One 9 px step moves the thumb by about a step of travel (plus the estimate error of a row first laid out).
            if (previous <= 1f) assertThat(previous - m.scrollFraction).isAtMost((9f + 20f) / travel)
            previous = m.scrollFraction
            scroll -= 9f
        }
        assertThat(previous).isAtMost(0.01f)
    }

    @Test fun `drag target round-trips the thumb within a pixel (header and variable rows)`() {
        val advances = withHeader(300f, 900f, 400f, 1200f, 550f, 700f)
        val viewport = 900f
        val estimate = 675f
        val barTravel = 1000f
        val known = advances.mapIndexed { i, a -> i to a }.toMap()
        val items = 1 + 6 * columns
        val seen = lazyScrollMetrics(items, columns, 1, visibleAt(advances, 0f, viewport), viewport, estimate, known)
        val starts = rowStarts(advances)
        for (finger in 0..1000 step 5) {
            val fraction = finger / barTravel
            val (index, offset) = lazyScrollTarget(seen, fraction)
            val row = rowOfItem(index, 1, columns)
            val landed = lazyScrollMetrics(
                items, columns, 1, visibleAt(advances, starts[row] + offset, viewport), viewport, estimate, known,
            )
            assertThat(landed.firstRow).isEqualTo(row)
            assertThat(abs(landed.scrollFraction * barTravel - finger)).isAtMost(1.0f)
        }
    }

    @Test fun `target maps a fraction onto a header, a note row and an offset`() {
        val metrics = lazyScrollMetrics(
            itemCount = 10, columns = columns, headerItems = 1,
            rows = listOf(GridRow(0, 0, 100f), GridRow(1, 100, 500f), GridRow(2, 600, 400f)),
            viewportExtent = 600f, estimatedRowAdvance = 400f,
        )
        // 6 rows: 100 + 500 + 400 + 3 * 400 = 2200 px of content, 1600 px of travel.
        assertThat(metrics.rows).isEqualTo(6)
        assertThat(metrics.contentExtent).isWithin(0.01f).of(2200f)
        assertThat(lazyScrollTarget(metrics, 0f)).isEqualTo(0 to 0)
        assertThat(lazyScrollTarget(metrics, 50f / 1600f)).isEqualTo(0 to 50) // inside the header row
        assertThat(lazyScrollTarget(metrics, 100f / 1600f)).isEqualTo(1 to 0) // first note row
        assertThat(lazyScrollTarget(metrics, 800f / 1600f)).isEqualTo(3 to 200) // row 2 starts at item 3
    }

    @Test fun `row index and row count handle full-span headers`() {
        assertThat(rowOfItem(0, 1, 2)).isEqualTo(0)
        assertThat(rowOfItem(1, 1, 2)).isEqualTo(1)
        assertThat(rowOfItem(2, 1, 2)).isEqualTo(1)
        assertThat(rowOfItem(3, 1, 2)).isEqualTo(2)
        assertThat(totalRows(5, 2, 1)).isEqualTo(3)
        assertThat(totalRows(6, 2, 0)).isEqualTo(3)
        assertThat(totalRows(0, 2, 0)).isEqualTo(0)
    }

    @Test fun `laid-out items group into rows with the gap in the advance`() {
        val rows = gridRows(
            listOf(
                GridItemPosition(index = 0, top = 0, height = 100), // header row
                GridItemPosition(index = 1, top = 140, height = 300),
                GridItemPosition(index = 2, top = 140, height = 250),
                GridItemPosition(index = 3, top = 500, height = 400),
            ),
            columns = 2, headerItems = 1,
        )
        assertThat(rows).containsExactly(
            GridRow(0, 0, 140f),
            GridRow(1, 140, 360f),
            GridRow(2, 500, 400f), // last row: its own height
        ).inOrder()
        // Headers are shorter: the frozen estimate is the average of the note rows only.
        assertThat(measuredRowAdvance(rows, headerItems = 1)).isWithin(0.001f).of(380f)
        assertThat(measuredRowAdvance(rows)).isWithin(0.001f).of(300f)
    }

    @Test fun `stable estimate only changes when the layout changes`() {
        val first = stableScrollEstimate(ScrollEstimate(), itemCount = 40, columns = 2, headerItems = 0, measuredRowAdvance = 100f)
        assertThat(first).isEqualTo(ScrollEstimate(40, 2, 0, 100f))
        val afterScroll = stableScrollEstimate(first, itemCount = 40, columns = 2, headerItems = 0, measuredRowAdvance = 240f)
        assertThat(afterScroll).isEqualTo(first)
        val afterDeletion = stableScrollEstimate(afterScroll, itemCount = 39, columns = 2, headerItems = 0, measuredRowAdvance = 105f)
        assertThat(afterDeletion).isEqualTo(ScrollEstimate(39, 2, 0, 105f))
        val afterColumns = stableScrollEstimate(afterDeletion, itemCount = 39, columns = 1, headerItems = 0, measuredRowAdvance = 210f)
        assertThat(afterColumns).isEqualTo(ScrollEstimate(39, 1, 0, 210f))
    }

    @Test fun `holder keeps its estimate across frames`() {
        val holder = ScrollEstimateHolder()
        assertThat(holder.estimateFor(40, 2, 0, 100f)).isEqualTo(100f)
        assertThat(holder.estimateFor(40, 2, 0, 240f)).isEqualTo(100f)
        assertThat(holder.estimateFor(39, 2, 0, 105f)).isEqualTo(105f)
    }

    @Test fun `list layout maps one item per row`() {
        val metrics = lazyScrollMetrics(
            itemCount = 10, columns = 1, headerItems = 0,
            rows = listOf(GridRow(0, -250, 500f), GridRow(1, 250, 300f)),
            viewportExtent = 800f, estimatedRowAdvance = 400f,
        )
        assertThat(metrics.rows).isEqualTo(10)
        assertThat(metrics.firstRow).isEqualTo(0)
        // 500 + 300 + 8 * 400 = 4000 px, 3200 px of travel; 250 px scrolled.
        assertThat(metrics.scrollFraction).isWithin(0.0001f).of(250f / 3200f)
        assertThat(lazyScrollTarget(metrics, 900f / 3200f)).isEqualTo(2 to 100)
    }

    @Test fun `drag target is monotonic in the fraction`() {
        val advances = mapOf(0 to 300f, 1 to 900f, 2 to 400f, 3 to 1200f, 4 to 550f, 5 to 700f)
        val metrics = LazyScrollMetrics(
            itemCount = 12, columns = 2, rows = 6, rowAdvances = advances, rowEstimate = 600f,
            viewport = 900f, contentExtent = advances.values.sum(),
        )
        var previous = -1f
        for (i in 0..100) {
            val (index, offset) = lazyScrollTarget(metrics, i / 100f)
            val row = rowOfItem(index, metrics.headerItems, metrics.columns)
            val position = rowStarts(advances.values.toList())[row] + offset
            assertThat(position).isAtLeast(previous)
            previous = position
        }
    }

    @Test fun `target tolerates degenerate metrics`() {
        assertThat(lazyScrollTarget(LazyScrollMetrics(), 0.5f)).isEqualTo(0 to 0)
    }

    @Test fun `holder remembers advances and forgets them when the layout changes`() {
        val holder = ScrollEstimateHolder()
        holder.estimateFor(40, 2, 0, 100f)
        val known = holder.advancesWith(listOf(GridRow(0, 0, 100f), GridRow(1, 100, 110f), GridRow(2, 210, 90f)))
        assertThat(known).containsExactly(0, 100f, 1, 110f)
        holder.estimateFor(40, 2, 0, 240f)
        assertThat(holder.advancesWith(emptyList())).hasSize(2)
        holder.estimateFor(39, 2, 0, 105f)
        assertThat(holder.advancesWith(emptyList())).isEmpty()
    }
}

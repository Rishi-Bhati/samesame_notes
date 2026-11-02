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

    @Test fun `empty grid needs no bar`() {
        val metrics = lazyScrollMetrics(0, columns, 0, emptyList(), 800f)
        assertThat(metrics.thumbFraction).isEqualTo(1f)
        assertThat(metrics.scrollFraction).isEqualTo(0f)
        assertThat(metrics.rows).isEqualTo(0)
    }

    @Test fun `grid that fits fills the thumb`() {
        // 4 notes, 2 columns = 2 rows of 400 on an 800 viewport: nothing to scroll.
        val metrics = lazyScrollMetrics(4, columns, 0, rowsOf(400f, 400f), 800f)
        assertThat(metrics.rows).isEqualTo(2)
        assertThat(metrics.thumbFraction).isEqualTo(1f)
        assertThat(metrics.scrollFraction).isEqualTo(0f)
    }

    @Test fun `scroll position comes from the first row offset with the right sign`() {
        // Row 0 is 300 tall and scrolled 150 px past the top: half way through row 0.
        val metrics = lazyScrollMetrics(20, columns, 0, rowsOf(300f, 700f, firstTop = -150), 800f)
        assertThat(metrics.firstRow).isEqualTo(0)
        assertThat(metrics.rowOffset).isWithin(0.001f).of(150f)
        val maxRow = metrics.rows - metrics.visibleRows
        assertThat(metrics.scrollFraction).isWithin(0.0001f).of(0.5f / maxRow)
    }

    @Test fun `scrolling down increases the fraction`() {
        val top = lazyScrollMetrics(20, columns, 0, rowsOf(300f, 700f), 800f)
        val down = lazyScrollMetrics(20, columns, 0, rowsOf(300f, 700f, firstTop = -100), 800f)
        assertThat(down.scrollFraction).isGreaterThan(top.scrollFraction)
    }

    @Test fun `row boundary is continuous with variable row heights`() {
        val before = lazyScrollMetrics(
            12, columns, 0,
            visibleAt(variableRowHeights, scroll = 299f, viewport = 900f),
            900f, estimatedRowAdvance = variableEstimate,
        )
        val after = lazyScrollMetrics(
            12, columns, 0,
            visibleAt(variableRowHeights, scroll = 301f, viewport = 900f),
            900f, estimatedRowAdvance = variableEstimate,
        )
        assertThat(before.firstRow).isEqualTo(0)
        assertThat(after.firstRow).isEqualTo(1)
        // A row boundary is one row of travel on the bar, never a jump by the height difference of the two rows.
        assertThat(abs(after.scrollFraction - before.scrollFraction)).isLessThan(0.005f)
    }

    @Test fun `fraction is monotonic across rows of different heights`() {
        var previous = -1f
        var scroll = 0f
        val end = variableRowHeights.sum() - 900f
        while (scroll <= end) {
            val metrics = lazyScrollMetrics(
                12, columns, 0,
                visibleAt(variableRowHeights, scroll, 900f),
                900f, variableEstimate,
            )
            assertThat(metrics.scrollFraction).isAtLeast(previous)
            previous = metrics.scrollFraction
            scroll += 7f
        }
    }

    @Test fun `drag target round-trips the thumb within a pixel (variable rows)`() {
        val barTravel = 1000f
        val viewport = 900f
        val starts = rowStarts(variableRowHeights)
        val rowAdvances = variableRowHeights.mapIndexed { index, advance -> index to advance }.toMap()
        val visible = viewport / variableEstimate
        val rows = variableRowHeights.size
        val maxRow = rows - visible
        // What the bar sees while dragging when the target row is laid out (its real height is known).
        val metrics = LazyScrollMetrics(
            itemCount = rows * columns,
            columns = columns,
            rows = rows,
            rowAdvances = rowAdvances,
            rowEstimate = variableEstimate,
            visibleRows = visible,
        )
        for (finger in 0..1000 step 5) {
            val fraction = finger / barTravel
            val position = fraction * maxRow
            val row = position.toInt().coerceIn(0, rows - 1)
            val (index, offset) = lazyScrollTarget(metrics, fraction)
            assertThat(index).isEqualTo(row * columns)
            // The list lands there and the row it landed on is laid out, so it reports its real advance.
            val landed = lazyScrollMetrics(
                rows * columns, columns, 0,
                visibleAt(variableRowHeights, scroll = starts[row] + offset, viewport),
                viewport, variableEstimate,
            )
            assertThat(landed.firstRow).isEqualTo(row)
            assertThat(abs(landed.scrollFraction * barTravel - finger)).isAtMost(1.0f)
        }
    }

    @Test fun `real laid-out rows round-trip through the drag target`() {
        // At the top of the list rows 0 and 1 are laid out: grabbing the thumb there round-trips exactly.
        val metrics = lazyScrollMetrics(
            12, columns, 0,
            visibleAt(variableRowHeights, scroll = 0f, viewport = 900f),
            900f, variableEstimate,
        )
        val maxRow = metrics.rows - metrics.visibleRows
        val starts = rowStarts(variableRowHeights)
        for (row in listOf(0, 1)) {
            for (within in listOf(0f, 0.25f, 0.5f, 0.99f)) {
                val fraction = (row + within) / maxRow
                val (index, offset) = lazyScrollTarget(metrics, fraction)
                val landed = lazyScrollMetrics(
                    12, columns, 0,
                    visibleAt(variableRowHeights, scroll = starts[row] + offset, viewport = 900f)
                        .filter { it.index >= row },
                    900f, variableEstimate,
                )
                // Only meaningful while the target row is the first laid-out row.
                if (landed.firstRow != row) continue
                assertThat(index).isEqualTo(row * columns)
                assertThat(abs(landed.scrollFraction - fraction) * 1000f).isAtMost(1.0f)
            }
        }
    }

    @Test fun `inverse uses variable row advances when they are known`() {
        val metrics = LazyScrollMetrics(
            thumbFraction = 0.25f,
            itemCount = 8,
            columns = 2,
            rows = 4,
            firstRow = 0,
            rowOffset = 0f,
            rowAdvance = 300f,
            rowAdvances = mapOf(0 to 300f, 1 to 900f, 2 to 400f, 3 to 1200f),
            rowEstimate = 600f,
            visibleRows = 1f,
        )
        val maxRow = 3f
        // Half way through row 1 (advance 900) and half way through row 2 (advance 400).
        assertThat(lazyScrollTarget(metrics, 1.5f / maxRow)).isEqualTo(2 to 450)
        assertThat(lazyScrollTarget(metrics, 2.5f / maxRow)).isEqualTo(4 to 200)
        // The end of the thumb is the last row's top: the list cannot start a row past the last one.
        assertThat(lazyScrollTarget(metrics, 1f)).isEqualTo(6 to 0)
    }

    @Test fun `target maps a fraction onto a row, a header and a within-row offset`() {
        val metrics = lazyScrollMetrics(
            itemCount = 10, columns = columns, headerItems = 1,
            rows = listOf(GridRow(0, 0, 100f), GridRow(1, 100, 500f), GridRow(2, 600, 400f)),
            viewportExtent = 600f, estimatedRowAdvance = 400f,
        )
        // 1 header row + ceil(9 / 2) = 6 rows; 600 / 400 = 1.5 rows visible => 4.5 rows of travel.
        assertThat(metrics.rows).isEqualTo(6)
        assertThat(metrics.headerItems).isEqualTo(1)
        val maxRow = metrics.rows - metrics.visibleRows
        val (index, offset) = lazyScrollTarget(metrics, 2.5f / maxRow)
        assertThat(index).isEqualTo(3) // row 2 starts at item 3 (header row + two-item rows)
        assertThat(offset).isWithin(1).of(200) // half of row 2's 400 advance
        assertThat(lazyScrollTarget(metrics, 0f)).isEqualTo(0 to 0)
        assertThat(lazyScrollTarget(metrics, 0.2f / maxRow)).isEqualTo(0 to 20) // header row start
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
        // 1 column: the same row model is the plain list.
        val metrics = lazyScrollMetrics(
            itemCount = 10, columns = 1, headerItems = 0,
            rows = listOf(GridRow(0, -250, 500f), GridRow(1, 250, 300f)),
            viewportExtent = 800f, estimatedRowAdvance = 400f,
        )
        assertThat(metrics.rows).isEqualTo(10)
        assertThat(metrics.firstRow).isEqualTo(0)
        val maxRow = metrics.rows - metrics.visibleRows
        assertThat(metrics.scrollFraction).isWithin(0.0001f).of(0.5f / maxRow)
        assertThat(lazyScrollTarget(metrics, 2f / maxRow)).isEqualTo(2 to 0)
    }

    @Test fun `drag target is monotonic in the fraction`() {
        val metrics = LazyScrollMetrics(
            itemCount = 12, columns = 2, rows = 6,
            rowAdvances = mapOf(0 to 300f, 1 to 900f, 2 to 400f, 3 to 1200f, 4 to 550f, 5 to 700f),
            rowEstimate = 600f, visibleRows = 1.5f,
        )
        var previous = -1f
        for (i in 0..100) {
            val (index, offset) = lazyScrollTarget(metrics, i / 100f)
            val row = rowOfItem(index, metrics.headerItems, metrics.columns)
            val advance = metrics.rowAdvances[row] ?: metrics.rowEstimate
            val position = row + offset / advance
            assertThat(position).isAtLeast(previous)
            previous = position
        }
    }

    @Test fun `target tolerates degenerate metrics`() {
        assertThat(lazyScrollTarget(LazyScrollMetrics(), 0.5f)).isEqualTo(0 to 0)
    }
}

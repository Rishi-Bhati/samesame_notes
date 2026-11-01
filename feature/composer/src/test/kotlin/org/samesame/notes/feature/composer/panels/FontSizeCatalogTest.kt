package org.samesame.notes.feature.composer.panels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FontSizeCatalogTest {

    @Test fun headingRequiresItsExactSizeAndBold() {
        assertThat(TextSizeCatalog.headingFor(32f, bold = true)).isEqualTo(TextSizeCatalog.Heading.H1)
        assertThat(TextSizeCatalog.headingFor(24f, bold = true)).isEqualTo(TextSizeCatalog.Heading.H2)
        assertThat(TextSizeCatalog.headingFor(20f, bold = true)).isEqualTo(TextSizeCatalog.Heading.H3)
        assertThat(TextSizeCatalog.headingFor(32f, bold = false)).isNull()
        assertThat(TextSizeCatalog.headingFor(17f, bold = true)).isNull()
        assertThat(TextSizeCatalog.headingFor(null, bold = true)).isNull()
    }

    @Test fun headingSizesArePartOfTheList() {
        TextSizeCatalog.Heading.entries.forEach { assertThat(TextSizeCatalog.SIZES).contains(it.size) }
        assertThat(TextSizeCatalog.SIZES).contains(TextSizeCatalog.BODY_SIZE)
        assertThat(TextSizeCatalog.SIZES).isInOrder()
        assertThat(TextSizeCatalog.SIZES).containsNoDuplicates()
    }

    @Test fun customSizesAreOfferedInPlace() {
        assertThat(TextSizeCatalog.menuSizes(13f)).contains(13)
        assertThat(TextSizeCatalog.menuSizes(13f)).isInOrder()
        assertThat(TextSizeCatalog.menuSizes(13f)).hasSize(TextSizeCatalog.SIZES.size + 1)
        assertThat(TextSizeCatalog.menuSizes(17f)).isEqualTo(TextSizeCatalog.SIZES)
    }

    @Test fun flatRowsMapToPicks() {
        val sizes = TextSizeCatalog.SIZES
        assertThat(FontSizeRows.pick(0, sizes)).isEqualTo(FontSizePick.Heading(TextSizeCatalog.Heading.H1))
        assertThat(FontSizeRows.pick(2, sizes)).isEqualTo(FontSizePick.Heading(TextSizeCatalog.Heading.H3))
        assertThat(FontSizeRows.pick(FontSizeRows.BODY_INDEX, sizes)).isEqualTo(FontSizePick.Body)
        assertThat(FontSizeRows.pick(FontSizeRows.SIZE_START, sizes)).isEqualTo(FontSizePick.Size(6))
        assertThat(FontSizeRows.pick(FontSizeRows.count(sizes) - 1, sizes)).isEqualTo(FontSizePick.Size(96))
        assertThat(FontSizeRows.pick(-1, sizes)).isNull()
        assertThat(FontSizeRows.pick(FontSizeRows.count(sizes), sizes)).isNull()
    }

    @Test fun rowAtUsesRootBounds() {
        val rows = listOf(
            RowBounds(0, top = 100f, bottom = 144f),
            RowBounds(1, top = 144f, bottom = 188f),
            RowBounds(3, top = 200f, bottom = 244f),
        )
        assertThat(FontSizeRows.rowAt(120f, rows)).isEqualTo(0)
        assertThat(FontSizeRows.rowAt(144f, rows)).isEqualTo(1)
        assertThat(FontSizeRows.rowAt(243.9f, rows)).isEqualTo(3)
        assertThat(FontSizeRows.rowAt(244f, rows)).isEqualTo(-1)
        assertThat(FontSizeRows.rowAt(190f, rows)).isEqualTo(-1)
        assertThat(FontSizeRows.rowAt(0f, rows)).isEqualTo(-1)
    }

    @Test fun holdTracksTheFingerRow() {
        val hold = FontSizeHoldState()
        hold.reportRow(RowBounds(0, 100f, 144f))
        hold.reportRow(RowBounds(1, 144f, 188f))
        assertThat(hold.moveTo(0f, 160f)).isTrue()
        assertThat(hold.highlighted).isEqualTo(1)
        assertThat(hold.moveTo(0f, 161f)).isFalse()
        assertThat(hold.moveTo(0f, 10f)).isTrue()
        assertThat(hold.highlighted).isEqualTo(-1)
        hold.clear()
        assertThat(hold.moveTo(0f, 160f)).isFalse()
    }
}

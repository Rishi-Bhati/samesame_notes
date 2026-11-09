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

    @Test fun theGlideAnchorIsTheAppliedRow() {
        val sizes = TextSizeCatalog.SIZES
        assertThat(FontSizeRows.anchorRow(TextSizeCatalog.Heading.H1, 32, sizes)).isEqualTo(0)
        assertThat(FontSizeRows.anchorRow(TextSizeCatalog.Heading.H3, 20, sizes)).isEqualTo(2)
        // Body text (17) anchors on the "17" row, so the first steps reach 16 and 18.
        assertThat(FontSizeRows.anchorRow(null, 17, sizes)).isEqualTo(FontSizeRows.SIZE_START + sizes.indexOf(17))
        assertThat(FontSizeRows.anchorRow(null, 6, sizes)).isEqualTo(FontSizeRows.SIZE_START)
        assertThat(FontSizeRows.anchorRow(null, 13, sizes)).isEqualTo(FontSizeRows.BODY_INDEX)
        val custom = TextSizeCatalog.menuSizes(13f)
        assertThat(FontSizeRows.anchorRow(null, 13, custom)).isEqualTo(FontSizeRows.SIZE_START + custom.indexOf(13))
    }
}

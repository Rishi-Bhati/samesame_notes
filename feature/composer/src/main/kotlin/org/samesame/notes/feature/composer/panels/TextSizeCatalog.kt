package org.samesame.notes.feature.composer.panels

import org.samesame.notes.core.model.richtext.TextSizeCatalog as ModelTextSizeCatalog

object TextSizeCatalog {
    val SIZES: List<Int> get() = ModelTextSizeCatalog.SIZES
    val BODY_SIZE: Int get() = ModelTextSizeCatalog.BODY_SIZE

    typealias Heading = ModelTextSizeCatalog.Heading

    fun headingFor(fontSize: Float?, bold: Boolean): Heading? =
        ModelTextSizeCatalog.headingFor(fontSize, bold)

    fun selectedSize(fontSize: Float?): Int =
        ModelTextSizeCatalog.selectedSize(fontSize)

    fun menuSizes(fontSize: Float?): List<Int> =
        ModelTextSizeCatalog.menuSizes(fontSize)
}

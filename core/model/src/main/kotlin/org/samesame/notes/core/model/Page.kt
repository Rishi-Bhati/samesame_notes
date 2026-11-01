package org.samesame.notes.core.model

import kotlinx.serialization.Serializable

/** Background pattern drawn under a page's content. */
@Serializable
enum class PageTemplate {
    Blank,
    LinesNarrow, LinesMedium, LinesWide,
    GridNarrow, GridMedium, GridWide,
    DotsNarrow, DotsMedium, DotsWide,
    Cornell,
    Music,
    TodoList,
    Weekly,
    Monthly,
}

/** Height ÷ width for fixed pages. */
@Serializable
enum class PageRatio(val heightOverWidth: Float) {
    A4Portrait(1.4142857f),
    A4Landscape(1f / 1.4142857f),
    Long(2f),
    /** The drawing sheet of Insert > Drawing (1:1.5); never a note's page ratio. */
    DrawingSheet(1.5f),
}

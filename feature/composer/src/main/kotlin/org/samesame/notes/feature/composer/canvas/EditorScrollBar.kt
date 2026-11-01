package org.samesame.notes.feature.composer.canvas

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.samesame.notes.core.designsystem.component.FastScrollBar

/**
 * The editor's fast scroll bar (as in the reference editor): the shared [FastScrollBar] (the home lists use the same
 * one), shown when the editor opens, thumb-only touch (the canvas under the edge is for writing), the note accent
 * #F46A4E while held, and the page bubble ("n/N") while dragging in pages mode. Vertical or horizontal with the page
 * layout. Scroll and drag are read in the draw / layout phases only.
 */
@Composable
internal fun EditorScrollBar(
    scrollFraction: () -> Float,
    horizontal: Boolean,
    onScrub: (Float) -> Unit,
    pageLabel: (() -> String)?,
    modifier: Modifier = Modifier,
) {
    FastScrollBar(
        scrollFraction = scrollFraction,
        onScrub = onScrub,
        modifier = modifier,
        horizontal = horizontal,
        showOnStart = true,
        label = pageLabel,
    )
}

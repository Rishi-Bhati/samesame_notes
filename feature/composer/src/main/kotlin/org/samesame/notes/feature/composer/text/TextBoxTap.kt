package org.samesame.notes.feature.composer.text

import org.samesame.notes.core.ink.page.ElementId

/** What a finger tap on a text box does while no box is being edited. */
enum class TextBoxTapAction {
    /** First tap: select the box with the same chrome a lasso selection gives it. */
    Select,

    /** Tap on the already-selected box: enter text editing. */
    Edit,
}

/**
 * Tapping an unselected text box selects it (handles, options menu, move/resize/rotate); tapping the selected box
 * again edits its text. Keeping this pure makes the transition testable without Compose.
 */
internal fun textBoxTapAction(
    selectedPageId: String?,
    selectedIds: Set<ElementId>,
    pageId: String,
    id: ElementId,
): TextBoxTapAction = if (selectedPageId == pageId && id in selectedIds) TextBoxTapAction.Edit else TextBoxTapAction.Select

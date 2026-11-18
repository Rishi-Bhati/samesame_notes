package org.samesame.notes.feature.composer.pages

/**
 * Facts about a page needed to determine whether it is empty and can be deleted.
 */
data class PageFacts(
    val id: String,
    val hasElements: Boolean,
    val hasPdf: Boolean,
    val hasImageTemplate: Boolean,
    val bookmarked: Boolean,
) {
    /** True when this page holds no ink/objects, no PDF, no background picture, and is not bookmarked. */
    val isEmpty: Boolean
        get() = !hasElements && !hasPdf && !hasImageTemplate && !bookmarked
}

/**
 * Pure policy deciding which empty trailing pages should be deleted after a user Backspace.
 *
 * Rule: after a Backspace that moves the caret from page N ([caretPageBefore]) to a smaller page M
 * ([caretPageAfter]), delete every page after the page holding the end of the text ([lastTextPage])
 * that is empty, starting from the LAST page and stopping at the first non-empty one, but never delete
 * a page with index <= M and never the only page.
 */
fun pagesToRemoveAfterBackspace(
    pages: List<PageFacts>,
    caretPageBefore: Int,
    caretPageAfter: Int,
    lastTextPage: Int,
): List<String> {
    if (pages.size <= 1) return emptyList()
    if (caretPageAfter >= caretPageBefore) return emptyList()
    if (caretPageAfter < 0) return emptyList()

    val toRemove = mutableListOf<String>()
    for (i in pages.indices.reversed()) {
        if (i <= caretPageAfter || i <= lastTextPage) break
        val page = pages[i]
        if (!page.isEmpty) break
        toRemove.add(page.id)
    }
    return toRemove.reversed()
}

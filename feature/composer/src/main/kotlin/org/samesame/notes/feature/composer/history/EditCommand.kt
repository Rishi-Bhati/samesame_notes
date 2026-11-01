package org.samesame.notes.feature.composer.history

import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.model.document.NoteObject
import org.samesame.notes.core.model.document.NoteRecording
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.RichTextOps
import java.util.IdentityHashMap

/**
 * A reversible delta (doc 4.4). Commands hold references to what they touched, never snapshots.
 * They are *recorded* after the change was already applied; [redo] / [undo] re-apply it.
 */
sealed interface EditCommand {
    fun redo(t: EditTarget)
    fun undo(t: EditTarget)

    /** Typing coalescing; null when [next] can't be folded into this command. */
    fun mergeWith(next: EditCommand): EditCommand? = null

    /** Pages whose layer must be loaded before this command can run. */
    val pageIds: Set<String> get() = emptySet()

    /** Strokes/elements kept alive by this command (history eviction past 50k, doc 4.4). */
    val weight: Int get() = 0
}

// ------------------------------------------------------------------------------------------------ ink / objects

/** Shared delta of a page element list: [removed] are old positions, [added] are final positions. */
sealed class ElementDelta(
    val page: String,
    val removed: List<IndexedElement>,
    val added: List<IndexedElement>,
) : EditCommand {
    override val pageIds: Set<String> get() = setOf(page)
    override val weight: Int get() = removed.size + added.size

    override fun redo(t: EditTarget) = swap(t, removed, added)
    override fun undo(t: EditTarget) = swap(t, added, removed)

    private fun swap(t: EditTarget, out: List<IndexedElement>, into: List<IndexedElement>) {
        if (out.isNotEmpty()) t.removeElements(page, out.mapTo(HashSet()) { it.element.id })
        if (into.isNotEmpty()) t.insertElements(page, into.sortedBy { it.index })
    }
}

class AddElements(page: String, items: List<IndexedElement>) : ElementDelta(page, emptyList(), items)
class RemoveElements(page: String, items: List<IndexedElement>) : ElementDelta(page, items, emptyList())
class ReplaceElements(page: String, removed: List<IndexedElement>, added: List<IndexedElement>) :
    ElementDelta(page, removed, added) {
    companion object {
        /**
         * Delta between two element lists, by instance identity (a transformed element keeps its id but is a new
         * instance). This is how an `EraseResult`, a bake or a restyle is wrapped: `diff(old, result.elements)`.
         * Null when nothing changed.
         */
        fun diff(page: String, old: List<PageElement>, new: List<PageElement>): ElementDelta? {
            val oldSet = IdentityHashMap<PageElement, Boolean>().also { m -> old.forEach { m[it] = true } }
            val newSet = IdentityHashMap<PageElement, Boolean>().also { m -> new.forEach { m[it] = true } }
            val removed = old.withIndex().filter { !newSet.containsKey(it.value) }.map { IndexedElement(it.index, it.value) }
            val added = new.withIndex().filter { !oldSet.containsKey(it.value) }.map { IndexedElement(it.index, it.value) }
            return when {
                removed.isEmpty() && added.isEmpty() -> null
                removed.isEmpty() -> AddElements(page, added)
                added.isEmpty() -> RemoveElements(page, removed)
                else -> ReplaceElements(page, removed, added)
            }
        }
    }
}

class Reorder(val page: String, val before: List<ElementId>, val after: List<ElementId>) : EditCommand {
    override val pageIds: Set<String> get() = setOf(page)
    override fun redo(t: EditTarget) = t.setElementOrder(page, after)
    override fun undo(t: EditTarget) = t.setElementOrder(page, before)
}

/** An inline object of the body appeared, changed or disappeared ([before] / [after] null). */
class ObjectChange(val id: String, val before: NoteObject?, val after: NoteObject?) : EditCommand {
    override fun redo(t: EditTarget) = t.setInlineObject(id, after)
    override fun undo(t: EditTarget) = t.setInlineObject(id, before)
}

/** The note-level recording list changed (a recording finished, or the list's delete). */
class RecordingsChange(val before: List<NoteRecording>, val after: List<NoteRecording>) : EditCommand {
    override fun redo(t: EditTarget) = t.setRecordings(after)
    override fun undo(t: EditTarget) = t.setRecordings(before)
}

// ------------------------------------------------------------------------------------------------ pages

/**
 * The page list changed. [detached] holds the layers of pages removed by the change so undo can bring them back.
 * New pages need no content: the store creates an empty layer when the list first mentions them.
 */
class PageListChange(
    val before: List<PageSpec>,
    val after: List<PageSpec>,
    val detached: List<PageContent> = emptyList(),
) : EditCommand {
    override val weight: Int get() = detached.sumOf { it.elements.size }

    override fun redo(t: EditTarget) {
        t.setPageSpecs(after)
        detached.forEach { t.detachPage(it.pageId) }
    }

    override fun undo(t: EditTarget) {
        t.setPageSpecs(before)
        detached.forEach { t.attachPage(it) }
    }
}

/**
 * Pages pasted as new pages (page sorter Paste): [before] / [after] are the page lists around the paste, [added] the new
 * layers. Undo takes them out of the note (kept for redo); redo puts them back. One undo step.
 */
class PagePasteChange(
    val before: List<PageSpec>,
    val after: List<PageSpec>,
    val added: List<PageContent>,
) : EditCommand {
    override val pageIds: Set<String> get() = added.mapTo(LinkedHashSet()) { it.pageId }
    override val weight: Int get() = added.sumOf { it.elements.size }

    override fun redo(t: EditTarget) {
        added.forEach { t.attachPage(it) }
        t.setPageSpecs(after)
    }

    override fun undo(t: EditTarget) {
        t.setPageSpecs(before)
        added.forEach { t.detachPage(it.pageId) }
    }
}

class PageSpecChange(val id: String, val before: PageSpec, val after: PageSpec) : EditCommand {
    override fun redo(t: EditTarget) = t.replacePageSpec(after)
    override fun undo(t: EditTarget) = t.replacePageSpec(before)
}

/** Compound edit (paste, an erase drag, text that appends pages): one undo step. */
class Group(val commands: List<EditCommand>) : EditCommand {
    override val pageIds: Set<String> get() = commands.flatMapTo(LinkedHashSet()) { it.pageIds }
    override val weight: Int get() = commands.sumOf { it.weight }
    override fun redo(t: EditTarget) = commands.forEach { it.redo(t) }
    override fun undo(t: EditTarget) = commands.asReversed().forEach { it.undo(t) }
}

// ------------------------------------------------------------------------------------------------ text

/**
 * `[start, start + removed.length)` was replaced by [inserted] (touched-slice fragments, so paragraph styles
 * round-trip). Built from the text engine's edit events by `EditorSession.onTextEdit`.
 */
class TextEdit(
    val start: Int,
    val removed: RichText,
    val inserted: RichText,
    val selBefore: TextSel,
    val selAfter: TextSel,
    val composed: Boolean = false,
) : EditCommand {
    override fun redo(t: EditTarget) = t.body.replaceSlice(start, removed.length, inserted, selAfter)
    override fun undo(t: EditTarget) = t.body.replaceSlice(start, inserted.length, removed, selBefore)

    override fun mergeWith(next: EditCommand): EditCommand? {
        if (next !is TextEdit) return null
        val end = start + inserted.length
        return when {
            // Typing on: next inserts right where this one ended.
            next.removed.length == 0 && next.start == end ->
                TextEdit(start, removed, concat(inserted, next.inserted, joinFromSecond = true), selBefore, next.selAfter, composed || next.composed)

            // Backspace into what this edit inserted.
            next.inserted.length == 0 && next.start >= start && next.start + next.removed.length == end ->
                TextEdit(start, removed, RichTextOps.slice(inserted, 0, next.start - start), selBefore, next.selAfter, composed || next.composed)

            // Backspace past the start of this edit: what it inserted is gone again.
            next.inserted.length == 0 && next.start < start && next.start + next.removed.length == end ->
                TextEdit(
                    next.start,
                    concat(RichTextOps.slice(next.removed, 0, start - next.start), removed, joinFromSecond = true),
                    next.inserted, selBefore, next.selAfter, composed || next.composed,
                )

            // Forward-delete burst at the same caret.
            inserted.length == 0 && next.inserted.length == 0 && next.start == start ->
                TextEdit(start, concat(removed, next.removed, joinFromSecond = false), inserted, selBefore, next.selAfter, composed || next.composed)

            else -> null
        }
    }

    internal companion object {
        /** Joins two touched slices where [a] ends in the paragraph [b] starts in. */
        fun concat(a: RichText, b: RichText, joinFromSecond: Boolean): RichText {
            val joint = if (joinFromSecond) b.paragraphs.first() else a.paragraphs.last()
            return RichTextOps.normalise(
                RichText(a.text + b.text, a.runs + b.runs, a.paragraphs.dropLast(1) + joint + b.paragraphs.drop(1)),
            )
        }
    }
}

/** Character styles of `[start, start + before.length)` changed. */
class CharStyleChange(val start: Int, val before: RichText, val after: RichText, val selection: TextSel) : EditCommand {
    override fun redo(t: EditTarget) = t.body.restoreCharStyles(start, after, selection)
    override fun undo(t: EditTarget) = t.body.restoreCharStyles(start, before, selection)
}

/** Paragraphs `firstParagraph until firstParagraph + before.size` changed style. */
class ParagraphStyleChange(
    val firstParagraph: Int,
    val before: List<ParagraphStyle>,
    val after: List<ParagraphStyle>,
    val selection: TextSel,
) : EditCommand {
    override fun redo(t: EditTarget) = t.body.restoreParagraphStyles(firstParagraph, after, selection)
    override fun undo(t: EditTarget) = t.body.restoreParagraphStyles(firstParagraph, before, selection)
}

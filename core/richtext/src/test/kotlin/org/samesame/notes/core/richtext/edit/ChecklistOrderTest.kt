package org.samesame.notes.core.richtext.edit

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.RichTextOps
import org.samesame.notes.core.model.richtext.TextRun

/** Pure "move checked items to the bottom" rules: runs, nesting, indents and undo round-trips. */
class ChecklistOrderTest {

    private fun item(checked: Boolean = false, indent: Int = 0) = ParagraphStyle(checkbox = true, checked = checked, indent = indent)
    private val plain = ParagraphStyle()

    // ---------------------------------------------------------------- targets

    @Test fun tickMovesTheItemJustAfterTheLastUncheckedItem() {
        val styles = listOf(item(), item(), item())
        assertThat(ChecklistOrder.moveAfterToggle(styles, 0, true)).isEqualTo(ChecklistOrder.Move(0, 1, 2))
        assertThat(ChecklistOrder.orderAfterToggle(styles, 0, true)).containsExactly(1, 2, 0).inOrder()
        assertThat(ChecklistOrder.moveAfterToggle(styles, 1, true)).isEqualTo(ChecklistOrder.Move(1, 1, 2))
        // The last item is already below every unchecked one.
        assertThat(ChecklistOrder.moveAfterToggle(styles, 2, true)).isNull()
    }

    @Test fun nestedItemsMoveWithTheirParent() {
        val styles = listOf(item(), item(), item(indent = 1), item())
        assertThat(ChecklistOrder.moveAfterToggle(styles, 1, true)).isEqualTo(ChecklistOrder.Move(1, 2, 2))
        assertThat(ChecklistOrder.orderAfterToggle(styles, 1, true)).containsExactly(0, 3, 1, 2).inOrder()
    }

    @Test fun deeperNestingMovesWithTheTopItem() {
        val styles = listOf(item(), item(indent = 1), item(indent = 2), item(indent = 1), item())
        assertThat(ChecklistOrder.moveAfterToggle(styles, 0, true)).isEqualTo(ChecklistOrder.Move(0, 4, 1))
        assertThat(ChecklistOrder.orderAfterToggle(styles, 0, true)).containsExactly(4, 0, 1, 2, 3).inOrder()
    }

    @Test fun separateIndentLevelsAndPlainParagraphsAreSeparateRuns() {
        // A nested item does not put the two level-0 items in different runs.
        val nested = listOf(item(), item(indent = 1), item())
        assertThat(ChecklistOrder.moveAfterToggle(nested, 0, true)).isEqualTo(ChecklistOrder.Move(0, 2, 1))
        // A plain paragraph at the same indent ends the run: each side is a one-item run.
        val split = listOf(item(), plain, item())
        assertThat(ChecklistOrder.moveAfterToggle(split, 0, true)).isNull()
        assertThat(ChecklistOrder.moveAfterToggle(split, 2, true)).isNull()
        // A shallower paragraph also ends the run: the ticked level-1 item only sees its sibling under the same parent.
        val levels = listOf(item(), item(indent = 1), item(indent = 1), item(), item(indent = 1))
        assertThat(ChecklistOrder.moveAfterToggle(levels, 1, true)).isEqualTo(ChecklistOrder.Move(1, 1, 2))
        assertThat(ChecklistOrder.orderAfterToggle(levels, 1, true)).containsExactly(0, 2, 1, 3, 4).inOrder()
    }

    @Test fun aNestedItemOnlyReordersWithinItsOwnSiblings() {
        val styles = listOf(item(), item(), item(indent = 1), item(indent = 1))
        assertThat(ChecklistOrder.moveAfterToggle(styles, 2, true)).isEqualTo(ChecklistOrder.Move(2, 1, 3))
        assertThat(ChecklistOrder.orderAfterToggle(styles, 2, true)).containsExactly(0, 1, 3, 2).inOrder()
    }

    @Test fun allCheckedUntickMovesToTheStartOfTheRun() {
        val styles = listOf(item(checked = true), item(checked = true), item(checked = true))
        assertThat(ChecklistOrder.moveAfterToggle(styles, 1, false)).isEqualTo(ChecklistOrder.Move(1, 1, 0))
        assertThat(ChecklistOrder.orderAfterToggle(styles, 1, false)).containsExactly(1, 0, 2).inOrder()
    }

    @Test fun allCheckedTickJoinsTheCheckedGroup() {
        val styles = listOf(item(checked = true), item(), item(checked = true))
        assertThat(ChecklistOrder.moveAfterToggle(styles, 1, true)).isEqualTo(ChecklistOrder.Move(1, 1, 2))
    }

    @Test fun untickMovesBackToTheEndOfTheUncheckedGroup() {
        val styles = listOf(item(), item(checked = true), item(checked = true))
        assertThat(ChecklistOrder.moveAfterToggle(styles, 2, false)).isEqualTo(ChecklistOrder.Move(2, 1, 1))
        assertThat(ChecklistOrder.orderAfterToggle(styles, 2, false)).containsExactly(0, 2, 1).inOrder()
        // Already right after the last unchecked item: no move.
        assertThat(ChecklistOrder.moveAfterToggle(styles, 1, false)).isNull()
    }

    @Test fun aNonCheckboxParagraphIsNeverMoved() {
        assertThat(ChecklistOrder.moveAfterToggle(listOf(plain, plain), 0, true)).isNull()
        assertThat(ChecklistOrder.orderAfterToggle(emptyList(), 0, true)).isNull()
    }

    // ---------------------------------------------------------------- rich text

    @Test fun reorderCarriesTextCharStylesObjectsAndParagraphStyles() {
        val objectChar = org.samesame.notes.core.model.richtext.OBJECT_CHAR
        val objId = "photo-1"
        val bold = CharStyle(bold = true)
        val region = RichText(
            text = "Milk\nEgg${objectChar}\nBread",
            runs = listOf(TextRun(5, CharStyle()), TextRun(4, bold.copy(objectId = objId)), TextRun(6, CharStyle())),
            paragraphs = listOf(item(), item(), item()),
        )
        val after = listOf(item(checked = true), item(), item())
        val reordered = ChecklistOrder.reorder(region, listOf(1, 2, 0), after)

        assertThat(reordered.text).isEqualTo("Egg${objectChar}\nBread\nMilk")
        assertThat(reordered.paragraphs).containsExactly(item(), item(), item(checked = true)).inOrder()
        // normalise keeps the object's one-char run separate, so its objectId survives the move.
        assertThat(reordered.runs).containsExactly(
            TextRun(3, bold), TextRun(1, bold.copy(objectId = objId)), TextRun(11, CharStyle()),
        ).inOrder()
    }

    /** One edit carries the whole region: undo restores order and tick, redo replays them. */
    @Test fun aSingleEditUndoesAndRedoesTheMoveExactly() {
        val region = RichText(
            text = "Milk\nEggs\nBread",
            runs = listOf(TextRun(5, CharStyle()), TextRun(4, CharStyle(bold = true)), TextRun(6, CharStyle())),
            paragraphs = listOf(item(), item(), item()),
        )
        val after = listOf(item(checked = true), item(), item())
        val order = ChecklistOrder.orderAfterToggle(region.paragraphs, 0, true)!!
        val moved = ChecklistOrder.reorder(region, order, after)

        // Replace region with moved for redo, moved with region for undo (BodyText.redo/undo use the same primitives).
        fun apply(doc: RichText, oldLen: Int, frag: RichText): RichText {
            val replaced = RichTextOps.insertRich(RichTextOps.delete(doc, 0, oldLen), 0, RichText(frag.text, frag.runs))
            val styles = replaced.paragraphs.toMutableList()
            repeat((frag.paragraphs.size - styles.size).coerceAtLeast(0)) { styles += ParagraphStyle() }
            frag.paragraphs.forEachIndexed { i, p -> styles[i] = p }
            return RichTextOps.normalise(replaced.copy(paragraphs = styles))
        }
        assertThat(apply(region, moved.length, moved)).isEqualTo(moved)
        assertThat(apply(moved, region.length, region)).isEqualTo(region)
    }
}

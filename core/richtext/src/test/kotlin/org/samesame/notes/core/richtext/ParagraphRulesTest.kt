package org.samesame.notes.core.richtext

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.RichTextOps
import org.samesame.notes.core.model.richtext.TextAlign
import org.samesame.notes.core.richtext.edit.ParagraphRules
import kotlin.random.Random

class ParagraphRulesTest {

    private val bullet = ParagraphStyle(list = ListKind.Bullet)
    private val numbered = ParagraphStyle(list = ListKind.Numbered)
    private val checked = ParagraphStyle(list = ListKind.Checklist, checked = true)

    @Test fun matchesRichTextOpsOnRandomEdits() {
        val rnd = Random(7)
        val styles = listOf(ParagraphStyle(), bullet, numbered, checked, ParagraphStyle(align = TextAlign.Center, indent = 2))
        var doc = RichText.of("ab\ncd\nef")
        doc = doc.copy(paragraphs = listOf(bullet, checked, numbered))
        repeat(500) {
            val s = rnd.nextInt(doc.length + 1)
            val e = (s + rnd.nextInt(4)).coerceAtMost(doc.length)
            val ins = listOf("", "x", "\n", "y\nz", "\n\n").random(rnd)
            val first = RichTextOps.paragraphIndexAt(doc, s)
            val removedNl = doc.text.substring(s, e).count { it == '\n' }
            val expected = RichTextOps.replace(doc, s, e, ins)
            val actual = ParagraphRules.applyReplace(doc.paragraphs, first, removedNl, ins.count { it == '\n' })
            assertThat(actual).isEqualTo(expected.paragraphs)
            doc = expected
            if (rnd.nextInt(5) == 0) {
                val p = rnd.nextInt(doc.paragraphs.size)
                doc = doc.copy(paragraphs = doc.paragraphs.toMutableList().also { it[p] = styles.random(rnd) })
            }
        }
    }

    @Test fun enterInCheckedItemContinuesListUnchecked() {
        val out = ParagraphRules.applyReplace(listOf(checked), 0, 0, 1)
        assertThat(out).containsExactly(checked, checked.copy(checked = false)).inOrder()
    }

    @Test fun enterOnEmptyListItemEndsList() {
        assertThat(ParagraphRules.onEnter(bullet, paragraphIsEmpty = true)).isEqualTo(ParagraphRules.EnterAction.EndList)
        assertThat(ParagraphRules.onEnter(bullet, paragraphIsEmpty = false)).isEqualTo(ParagraphRules.EnterAction.InsertNewline)
        assertThat(ParagraphRules.onEnter(ParagraphStyle(), paragraphIsEmpty = true)).isEqualTo(ParagraphRules.EnterAction.InsertNewline)
    }

    @Test fun indentAndOutdentAreClamped() {
        var p = ParagraphStyle()
        repeat(Limits.MAX_INDENT_LEVEL + 3) { p = ParagraphRules.indent(p) }
        assertThat(p.indent).isEqualTo(Limits.MAX_INDENT_LEVEL)
        repeat(Limits.MAX_INDENT_LEVEL + 3) { p = ParagraphRules.outdent(p) }
        assertThat(p.indent).isEqualTo(0)
    }

    @Test fun numberingCountsConsecutiveItemsPerIndent() {
        val nested = numbered.copy(indent = 1)
        val list = listOf(numbered, numbered, nested, nested, numbered, ParagraphStyle(), numbered, bullet, numbered)
        assertThat(ParagraphRules.numbering(list).toList()).containsExactly(1, 2, 1, 2, 3, 0, 1, 0, 1).inOrder()
    }

    @Test fun deeperBulletDoesNotInterruptNumbering() {
        val list = listOf(numbered, bullet.copy(indent = 1), numbered)
        assertThat(ParagraphRules.numbering(list).toList()).containsExactly(1, 0, 2).inOrder()
    }

    @Test fun enterOnAnEmptyIndentedItemOutdentsFirst() {
        val p = ParagraphStyle(list = ListKind.Bullet, indent = 2)
        assertThat(ParagraphRules.onEnter(p, paragraphIsEmpty = true)).isEqualTo(ParagraphRules.EnterAction.Outdent)
        assertThat(ParagraphRules.onEnter(p.copy(indent = 0), true)).isEqualTo(ParagraphRules.EnterAction.EndList)
        assertThat(ParagraphRules.onEnter(p, false)).isEqualTo(ParagraphRules.EnterAction.InsertNewline)
    }
}

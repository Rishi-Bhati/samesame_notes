package org.samesame.notes.core.model.richtext

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.document.NoteJson

class RichTextOpsTest {
    private val bold = CharStyle(bold = true)
    private val plain = CharStyle()

    private fun rt(text: String, vararg runs: Pair<Int, CharStyle>) =
        RichTextOps.normalise(RichText(text, runs.map { TextRun(it.first, it.second) }))

    private fun RichText.assertValid() {
        assertThat(runs.sumOf { it.length }).isEqualTo(text.length)
        assertThat(runs.none { it.length == 0 }).isTrue()
        assertThat(paragraphs.size).isEqualTo(text.count { it == '\n' } + 1)
        assertThat(this).isEqualTo(RichTextOps.normalise(this))
    }

    @Test fun `normalise merges equal runs, drops empty, pads and truncates`() {
        val a = RichTextOps.normalise(RichText("abcd", listOf(TextRun(1, plain), TextRun(0, bold), TextRun(2, plain))))
        assertThat(a.runs).containsExactly(TextRun(4, plain))
    }

    @Test fun `normalise pads short runs and trims long ones`() {
        val padded = RichTextOps.normalise(RichText("abc", listOf(TextRun(1, bold))))
        assertThat(padded.runs).containsExactly(TextRun(3, bold))
        val trimmed = RichTextOps.normalise(RichText("a", listOf(TextRun(1, bold), TextRun(5, plain))))
        assertThat(trimmed.runs).containsExactly(TextRun(1, bold))
    }

    @Test fun `normalise empty text has no runs and one paragraph`() {
        val n = RichTextOps.normalise(RichText("", listOf(TextRun(3, bold)), emptyList()))
        assertThat(n.runs).isEmpty()
        assertThat(n.paragraphs).hasSize(1)
    }

    @Test fun `normalise resizes paragraphs to newline count`() {
        val n = RichTextOps.normalise(RichText("a\nb\nc", listOf(TextRun(5, plain)), listOf(ParagraphStyle(indent = 2))))
        assertThat(n.paragraphs).hasSize(3)
        assertThat(n.paragraphs[0].indent).isEqualTo(2)
        val t = RichTextOps.normalise(RichText("a", listOf(TextRun(1, plain)), List(4) { ParagraphStyle() }))
        assertThat(t.paragraphs).hasSize(1)
    }

    @Test fun `insert in the middle of a run keeps one merged run`() {
        val r = RichTextOps.insert(rt("abcd", 4 to bold), 2, "XY")
        assertThat(r.text).isEqualTo("abXYcd")
        assertThat(r.runs).containsExactly(TextRun(6, bold))
        r.assertValid()
    }

    @Test fun `insert with explicit style splits runs`() {
        val r = RichTextOps.insert(rt("abcd", 4 to plain), 2, "XY", bold)
        assertThat(r.runs).containsExactly(TextRun(2, plain), TextRun(2, bold), TextRun(2, plain)).inOrder()
    }

    @Test fun `insert inherits style of previous char, or next at offset zero`() {
        val base = rt("ab", 1 to bold, 1 to plain)
        assertThat(RichTextOps.insert(base, 1, "X").runs.first()).isEqualTo(TextRun(2, bold))
        assertThat(RichTextOps.insert(base, 0, "X").runs.first()).isEqualTo(TextRun(2, bold))
        assertThat(RichTextOps.insert(base, 2, "X").runs).containsExactly(TextRun(1, bold), TextRun(2, plain)).inOrder()
    }

    @Test fun `insert into empty text`() {
        val r = RichTextOps.insert(RichText(), 0, "hi", bold)
        assertThat(r.text).isEqualTo("hi")
        assertThat(r.runs).containsExactly(TextRun(2, bold))
        r.assertValid()
    }

    @Test fun `insert clamps offset`() {
        assertThat(RichTextOps.insert(rt("ab", 2 to plain), 99, "c").text).isEqualTo("abc")
        assertThat(RichTextOps.insert(rt("ab", 2 to plain), -5, "c").text).isEqualTo("cab")
    }

    @Test fun `newline splits paragraph and new one inherits list style unchecked`() {
        val base = RichText("abcd", listOf(TextRun(4, plain)), listOf(ParagraphStyle(list = ListKind.Checklist, checked = true, indent = 1)))
        val r = RichTextOps.insert(base, 2, "\n")
        assertThat(r.text).isEqualTo("ab\ncd")
        assertThat(r.paragraphs).containsExactly(
            ParagraphStyle(list = ListKind.Checklist, checked = true, indent = 1),
            ParagraphStyle(list = ListKind.Checklist, checked = false, indent = 1),
        ).inOrder()
        r.assertValid()
    }

    @Test fun `multi newline insert adds paragraphs in the middle`() {
        val base = RichText("ab\ncd", listOf(TextRun(5, plain)), listOf(ParagraphStyle(), ParagraphStyle(align = TextAlign.Right)))
        val r = RichTextOps.insert(base, 1, "x\ny\nz")
        assertThat(r.text).isEqualTo("ax\ny\nzb\ncd")
        assertThat(r.paragraphs).hasSize(4)
        assertThat(r.paragraphs[3].align).isEqualTo(TextAlign.Right)
        r.assertValid()
    }

    @Test fun `insert strips U+FFFC from plain text`() {
        val r = RichTextOps.insert(rt("ab", 2 to plain), 1, "x￼y")
        assertThat(r.text).isEqualTo("axyb")
    }

    @Test fun `insertObject adds a single-char run with objectId and not inherited`() {
        val r = RichTextOps.insertObject(rt("ab", 2 to bold), 1, "obj1")
        assertThat(r.text).isEqualTo("a￼b")
        assertThat(r.runs).containsExactly(
            TextRun(1, bold), TextRun(1, bold.copy(objectId = "obj1")), TextRun(1, bold),
        ).inOrder()
        // typing right after the object does not inherit the objectId
        val typed = RichTextOps.insert(r, 2, "z")
        assertThat(typed.runs.count { it.style.objectId != null }).isEqualTo(1)
        typed.assertValid()
    }

    @Test fun `two adjacent objects stay separate runs`() {
        val r = RichTextOps.insertObject(RichTextOps.insertObject(RichText(), 0, "o1"), 1, "o2")
        assertThat(r.runs.map { it.style.objectId }).containsExactly("o1", "o2").inOrder()
    }

    @Test fun `normalise clears objectId on non-object chars`() {
        val n = RichTextOps.normalise(RichText("a￼", listOf(TextRun(2, CharStyle(objectId = "o")))))
        assertThat(n.runs).containsExactly(TextRun(1, plain), TextRun(1, CharStyle(objectId = "o"))).inOrder()
    }

    @Test fun `delete within a run`() {
        val r = RichTextOps.delete(rt("abcdef", 2 to plain, 4 to bold), 1, 4)
        assertThat(r.text).isEqualTo("aef")
        assertThat(r.runs).containsExactly(TextRun(1, plain), TextRun(2, bold)).inOrder()
        r.assertValid()
    }

    @Test fun `delete merges runs that become adjacent`() {
        val r = RichTextOps.delete(rt("aXb", 1 to plain, 1 to bold, 1 to plain), 1, 2)
        assertThat(r.runs).containsExactly(TextRun(2, plain))
    }

    @Test fun `delete newline joins paragraphs keeping the first style`() {
        val base = RichText(
            "ab\ncd", listOf(TextRun(5, plain)),
            listOf(ParagraphStyle(list = ListKind.Bullet), ParagraphStyle(align = TextAlign.Center)),
        )
        val r = RichTextOps.delete(base, 2, 3)
        assertThat(r.text).isEqualTo("abcd")
        assertThat(r.paragraphs).containsExactly(ParagraphStyle(list = ListKind.Bullet))
        r.assertValid()
    }

    @Test fun `delete across several paragraphs`() {
        val base = RichText(
            "a\nb\nc\nd", listOf(TextRun(7, plain)),
            listOf(ParagraphStyle(indent = 1), ParagraphStyle(indent = 2), ParagraphStyle(indent = 3), ParagraphStyle(indent = 4)),
        )
        val r = RichTextOps.delete(base, 1, 5) // "\nb\nc" removed
        assertThat(r.text).isEqualTo("a\nd")
        assertThat(r.paragraphs.map { it.indent }).containsExactly(1, 4).inOrder()
    }

    @Test fun `delete removes inline object char and reversed or out-of-range args clamp`() {
        val base = RichTextOps.insertObject(rt("ab", 2 to plain), 1, "o")
        val r = RichTextOps.delete(base, 2, 1)
        assertThat(r.text).isEqualTo("ab")
        assertThat(r.runs.none { it.style.objectId != null }).isTrue()
        assertThat(RichTextOps.delete(rt("ab", 2 to plain), -3, 99).text).isEmpty()
    }

    @Test fun `delete everything leaves valid empty text`() {
        val r = RichTextOps.delete(rt("a\nb", 3 to bold), 0, 3)
        assertThat(r.text).isEmpty()
        assertThat(r.runs).isEmpty()
        assertThat(r.paragraphs).hasSize(1)
    }

    @Test fun `slice returns runs and paragraph styles of the range`() {
        val base = RichText(
            "ab\ncd", listOf(TextRun(2, bold), TextRun(3, plain)),
            listOf(ParagraphStyle(list = ListKind.Bullet), ParagraphStyle(align = TextAlign.Right)),
        )
        val s = RichTextOps.slice(base, 1, 4)
        assertThat(s.text).isEqualTo("b\nc")
        assertThat(s.runs).containsExactly(TextRun(1, bold), TextRun(2, plain)).inOrder()
        assertThat(s.paragraphs).containsExactly(ParagraphStyle(list = ListKind.Bullet), ParagraphStyle(align = TextAlign.Right)).inOrder()
        s.assertValid()
    }

    @Test fun `slice of collapsed or empty range is empty`() {
        assertThat(RichTextOps.slice(rt("abc", 3 to bold), 1, 1).text).isEmpty()
        assertThat(RichTextOps.slice(rt("abc", 3 to bold), 5, 9).text).isEmpty()
    }

    @Test fun `slice then insertRich round trips`() {
        val base = RichTextOps.applyCharStyle(rt("hello world", 11 to plain), 0, 5) { it.copy(bold = true) }
        val frag = RichTextOps.slice(base, 0, 5)
        val r = RichTextOps.insertRich(base, 11, frag)
        assertThat(r.text).isEqualTo("hello worldhello")
        assertThat(r.runs.last()).isEqualTo(TextRun(5, bold))
        r.assertValid()
    }

    @Test fun `slice keeps inline object runs`() {
        val base = RichTextOps.insertObject(rt("ab", 2 to plain), 1, "o")
        val s = RichTextOps.slice(base, 1, 2)
        assertThat(s.runs).containsExactly(TextRun(1, CharStyle(objectId = "o")))
    }

    @Test fun `applyCharStyle splits and then merges runs`() {
        val base = rt("abcdef", 6 to plain)
        val b = RichTextOps.applyCharStyle(base, 2, 4) { it.copy(bold = true) }
        assertThat(b.runs).containsExactly(TextRun(2, plain), TextRun(2, bold), TextRun(2, plain)).inOrder()
        val back = RichTextOps.applyCharStyle(b, 2, 4) { it.copy(bold = false) }
        assertThat(back.runs).containsExactly(TextRun(6, plain))
    }

    @Test fun `applyCharStyle across runs and extending adjacent`() {
        val base = rt("abcd", 2 to bold, 2 to plain)
        val r = RichTextOps.applyCharStyle(base, 1, 4) { it.copy(bold = true) }
        assertThat(r.runs).containsExactly(TextRun(4, bold))
    }

    @Test fun `applyCharStyle collapsed is no-op and clamps`() {
        val base = rt("abc", 3 to plain)
        assertThat(RichTextOps.applyCharStyle(base, 1, 1) { it.copy(bold = true) }).isEqualTo(base)
        assertThat(RichTextOps.applyCharStyle(base, -4, 50) { it.copy(italic = true) }.runs).containsExactly(TextRun(3, CharStyle(italic = true)))
    }

    @Test fun `applyCharStyle cannot change objectId`() {
        val base = RichTextOps.insertObject(rt("ab", 2 to plain), 1, "o")
        val r = RichTextOps.applyCharStyle(base, 0, 3) { it.copy(objectId = "evil", bold = true) }
        assertThat(r.runs.map { it.style.objectId }).containsExactly(null, "o", null).inOrder()
        assertThat(r.runs.all { it.style.bold }).isTrue()
    }

    @Test fun `applyCharStyle color and fontSize`() {
        val r = RichTextOps.applyCharStyle(rt("ab", 2 to plain), 0, 1) { it.copy(color = 0xFFFF0000, fontSize = 20f) }
        assertThat(r.runs.first().style.color).isEqualTo(0xFFFF0000)
        assertThat(r.runs.first().style.fontSize).isEqualTo(20f)
    }

    private val three = RichText("a\nb\nc", listOf(TextRun(5, plain)), List(3) { ParagraphStyle() })

    @Test fun `setParagraphStyle collapsed hits the caret paragraph`() {
        val r = RichTextOps.setParagraphStyle(three, 2, 2) { it.copy(align = TextAlign.Center) }
        assertThat(r.paragraphs.map { it.align }).containsExactly(TextAlign.Left, TextAlign.Center, TextAlign.Left).inOrder()
    }

    @Test fun `setParagraphStyle range covers all touched paragraphs`() {
        val r = RichTextOps.setParagraphStyle(three, 1, 5) { it.copy(align = TextAlign.Right) }
        assertThat(r.paragraphs.all { it.align == TextAlign.Right }).isTrue()
    }

    @Test fun `setParagraphStyle range ending right after newline excludes next paragraph`() {
        val r = RichTextOps.setParagraphStyle(three, 0, 2) { it.copy(align = TextAlign.Right) }
        assertThat(r.paragraphs.map { it.align }).containsExactly(TextAlign.Right, TextAlign.Left, TextAlign.Left).inOrder()
    }

    @Test fun `setParagraphStyle clamps indent`() {
        val up = RichTextOps.setParagraphStyle(three, 0, 0) { it.copy(indent = it.indent + 99) }
        assertThat(up.paragraphs[0].indent).isEqualTo(Limits.MAX_INDENT_LEVEL)
        val down = RichTextOps.setParagraphStyle(three, 0, 0) { it.copy(indent = it.indent - 1) }
        assertThat(down.paragraphs[0].indent).isEqualTo(0)
    }

    @Test fun `toggleList sets then clears`() {
        val on = RichTextOps.toggleList(three, 0, 5, ListKind.Bullet)
        assertThat(on.paragraphs.all { it.list == ListKind.Bullet }).isTrue()
        val off = RichTextOps.toggleList(on, 0, 5, ListKind.Bullet)
        assertThat(off.paragraphs.all { it.list == ListKind.None }).isTrue()
    }

    @Test fun `toggleList on mixed selection applies kind to all`() {
        val mixed = RichTextOps.toggleList(three, 0, 0, ListKind.Bullet)
        val r = RichTextOps.toggleList(mixed, 0, 5, ListKind.Bullet)
        assertThat(r.paragraphs.all { it.list == ListKind.Bullet }).isTrue()
    }

    @Test fun `toggleList switches kind and clears checked`() {
        val chk = RichTextOps.setParagraphStyle(
            RichTextOps.toggleList(three, 0, 0, ListKind.Checklist), 0, 0,
        ) { it.copy(checked = true) }
        assertThat(chk.paragraphs[0].checked).isTrue()
        val num = RichTextOps.toggleList(chk, 0, 0, ListKind.Numbered)
        assertThat(num.paragraphs[0]).isEqualTo(ParagraphStyle(list = ListKind.Numbered))
        val off = RichTextOps.toggleList(chk, 0, 0, ListKind.Checklist)
        assertThat(off.paragraphs[0]).isEqualTo(ParagraphStyle())
    }

    @Test fun `replace swaps range`() {
        val r = RichTextOps.replace(rt("abcd", 4 to bold), 1, 3, "XYZ")
        assertThat(r.text).isEqualTo("aXYZd")
        assertThat(r.runs).containsExactly(TextRun(5, bold))
    }

    @Test fun `paragraph helpers`() {
        assertThat(RichTextOps.paragraphIndexAt(three, 2)).isEqualTo(1)
        assertThat(RichTextOps.paragraphRange(three, 1)).isEqualTo(2 until 3)
        assertThat(RichTextOps.paragraphRange(three, 2)).isEqualTo(4 until 5)
    }

    @Test fun `json round trip`() {
        val r = RichTextOps.insertObject(
            RichTextOps.toggleList(
                RichTextOps.applyCharStyle(rt("hello\nworld", 11 to plain), 0, 3) { it.copy(bold = true, color = 0xFF112233) },
                0, 0, ListKind.Checklist,
            ),
            2, "obj",
        )
        val json = NoteJson.encodeToString(RichText.serializer(), r)
        assertThat(NoteJson.decodeFromString(RichText.serializer(), json)).isEqualTo(r)
    }
}

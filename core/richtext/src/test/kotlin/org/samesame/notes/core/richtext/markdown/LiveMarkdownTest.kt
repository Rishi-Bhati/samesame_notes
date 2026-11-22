package org.samesame.notes.core.richtext.markdown

import androidx.compose.ui.text.TextRange
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.designsystem.theme.SameSameDarkColors as DarkColors
import kotlin.random.Random

class LiveMarkdownTest {

    private fun names(type: Int): String = when (type) {
        LiveToken.MARKER -> "marker"
        in LiveToken.HEADING_1..LiveToken.HEADING_6 -> "h${LiveToken.headingLevel(type)}"
        LiveToken.BOLD -> "bold"
        LiveToken.ITALIC -> "italic"
        LiveToken.STRIKE -> "strike"
        LiveToken.CODE -> "code"
        LiveToken.LINK_TEXT -> "link"
        LiveToken.LINK_URL -> "url"
        LiveToken.IMAGE_ALT -> "alt"
        LiveToken.AUTOLINK -> "autolink"
        LiveToken.QUOTE_MARK -> "quote"
        LiveToken.QUOTE_TEXT -> "quoteText"
        LiveToken.BULLET -> "bullet"
        LiveToken.ORDERED -> "ordered"
        LiveToken.TASK_OPEN -> "task"
        LiveToken.TASK_DONE -> "done"
        LiveToken.TASK_DONE_TEXT -> "doneText"
        LiveToken.TASK_BULLET -> "taskBullet"
        LiveToken.FENCE -> "fence"
        LiveToken.CODE_BLOCK -> "codeBlock"
        LiveToken.RULE -> "rule"
        LiveToken.TABLE -> "table"
        LiveToken.TABLE_PIPE -> "pipe"
        LiveToken.TABLE_DELIM -> "delim"
        else -> "?$type"
    }

    /** Tokens of line [index] as `name:text`, in start order. */
    private fun tokens(text: String, index: Int = 0): List<String> {
        val doc = LiveMarkdownDocument.analyze(text)
        val l = doc.line(index)
        val base = doc.lineStart(index)
        return (0 until l.tokenCount)
            .sortedWith(compareBy({ l.tokenStart(it) }, { -l.tokenEnd(it) }))
            .map { names(l.tokenType(it)) + ":" + text.substring(base + l.tokenStart(it), base + l.tokenEnd(it)) }
    }

    private fun assertSameAsFresh(doc: LiveMarkdownDocument) {
        val fresh = LiveMarkdownDocument.analyze(doc.text)
        assertThat(doc.lineCount).isEqualTo(fresh.lineCount)
        assertThat(doc.tokenCount).isEqualTo(fresh.tokenCount)
        for (i in 0 until fresh.lineCount) {
            val a = doc.line(i)
            val b = fresh.line(i)
            assertThat(doc.lineStart(i)).isEqualTo(fresh.lineStart(i))
            assertThat(a.length).isEqualTo(b.length)
            assertThat(a.kind).isEqualTo(b.kind)
            assertThat(a.startState).isEqualTo(b.startState)
            assertThat(a.endState).isEqualTo(b.endState)
            assertThat(a.tokens.toList()).isEqualTo(b.tokens.toList())
        }
    }

    // ---- headings ---------------------------------------------------------------------------------------------

    @Test fun `a hash needs a space to make a heading`() {
        assertThat(tokens("# hello")).containsExactly("marker:# ", "h1:hello").inOrder()
        assertThat(tokens("#hello")).isEmpty()
        assertThat(LiveMarkdownDocument.analyze("#hello").line(0).kind).isEqualTo(LiveLineKind.PARAGRAPH)
    }

    @Test fun `heading levels one to six and seven hashes is text`() {
        assertThat(tokens("###### six")).containsExactly("marker:###### ", "h6:six").inOrder()
        assertThat(tokens("####### seven")).isEmpty()
        assertThat(tokens("## two ##")).containsExactly("marker:## ", "h2:two", "marker: ##").inOrder()
    }

    @Test fun `setext headings`() {
        assertThat(tokens("Title\n=====", 0)).containsExactly("h1:Title")
        assertThat(tokens("Title\n=====", 1)).containsExactly("marker:=====")
        assertThat(tokens("Sub\n---", 0)).containsExactly("h2:Sub")
        // Without a paragraph above, --- is a rule.
        assertThat(tokens("\n---", 1)).containsExactly("rule:---")
    }

    // ---- inline -------------------------------------------------------------------------------------------------

    @Test fun `bold italic strike and code`() {
        assertThat(tokens("a **b** c")).containsExactly("marker:**", "bold:b", "marker:**").inOrder()
        assertThat(tokens("*i* and _j_")).containsExactly("marker:*", "italic:i", "marker:*", "marker:_", "italic:j", "marker:_").inOrder()
        assertThat(tokens("~~gone~~")).containsExactly("marker:~~", "strike:gone", "marker:~~").inOrder()
        assertThat(tokens("use `x * y` here")).containsExactly("marker:`", "code:x * y", "marker:`").inOrder()
    }

    @Test fun `bold italic nested`() {
        assertThat(tokens("***both***")).containsExactly(
            "marker:*", "marker:**", "bold:both", "italic:**both**", "marker:**", "marker:*",
        )
    }

    @Test fun `intraword underscores and lone stars are text`() {
        assertThat(tokens("snake_case_name")).isEmpty()
        assertThat(tokens("2 * 3 * 4")).isEmpty()
        assertThat(tokens("**unclosed")).isEmpty()
    }

    @Test fun `escapes hide the backslash and keep the character literal`() {
        assertThat(tokens("\\*not\\*")).containsExactly("marker:\\", "marker:\\").inOrder()
    }

    @Test fun `links images and autolinks`() {
        assertThat(tokens("see [docs](https://x.org \"t\") now")).containsExactly(
            "marker:[", "link:docs", "marker:](", "url:https://x.org \"t\"", "marker:)",
        ).inOrder()
        assertThat(tokens("![cat](cat.png)")).containsExactly("marker:![", "alt:cat", "marker:](", "url:cat.png", "marker:)").inOrder()
        assertThat(tokens("go to https://example.com/a_b.")).containsExactly("autolink:https://example.com/a_b")
        assertThat(tokens("<https://a.b>")).containsExactly("marker:<", "autolink:https://a.b", "marker:>").inOrder()
        assertThat(tokens("[**bold** link](u)")).containsExactly(
            "marker:[", "link:**bold** link", "marker:**", "bold:bold", "marker:**", "marker:](", "url:u", "marker:)",
        ).inOrder()
    }

    @Test fun `link targets`() {
        val doc = LiveMarkdownDocument.analyze("a [t](<https://x.org/a b> \"title\") www.example.com <me@x.org>")
        assertThat(doc.linkAt(3)?.target).isEqualTo("https://x.org/a b")
        val www = doc.text.indexOf("www.")
        assertThat(doc.linkAt(www + 2)?.target).isEqualTo("https://www.example.com")
        assertThat(doc.linkAt(doc.text.indexOf("me@") + 1)?.target).isEqualTo("mailto:me@x.org")
        assertThat(doc.linkAt(0)).isNull()
    }

    // ---- blocks -------------------------------------------------------------------------------------------------

    @Test fun `quotes lists and tasks`() {
        assertThat(tokens("> said")).containsExactly("quote:>", "quoteText:said").inOrder()
        assertThat(tokens("- item")).containsExactly("bullet:-")
        assertThat(tokens("12. item")).containsExactly("ordered:12.")
        assertThat(tokens("- [ ] todo")).containsExactly("taskBullet:-", "task:[ ]").inOrder()
        assertThat(tokens("- [x] done")).containsExactly("taskBullet:-", "done:[x]", "doneText:done").inOrder()
        assertThat(tokens("> - [ ] nested")).containsExactly("quote:>", "taskBullet:-", "task:[ ]", "quoteText:nested").inOrder()
        assertThat(tokens("-not a list")).isEmpty()
        val doc = LiveMarkdownDocument.analyze("- [ ] todo")
        assertThat(doc.line(0).prefixEnd).isEqualTo(5)
        assertThat(doc.taskBoxAt(0)).isEqualTo(2)
    }

    @Test fun `fenced code block`() {
        val text = "```kotlin\nval a = **b**\n```\nafter **x**"
        assertThat(tokens(text, 0)).containsExactly("fence:```kotlin")
        assertThat(tokens(text, 1)).containsExactly("codeBlock:val a = **b**")
        assertThat(tokens(text, 2)).containsExactly("fence:```")
        assertThat(tokens(text, 3)).containsExactly("marker:**", "bold:x", "marker:**").inOrder()
    }

    @Test fun `rules and tables`() {
        assertThat(tokens("***")).containsExactly("rule:***")
        assertThat(tokens("- - -")).containsExactly("rule:- - -")
        val table = "| a | b |\n|---|:-:|\n| 1 | 2 |\nafter"
        assertThat(tokens(table, 0)).contains("table:| a | b |")
        assertThat(tokens(table, 0).count { it == "pipe:|" }).isEqualTo(3)
        assertThat(tokens(table, 1)).containsExactly("delim:|---|:-:|")
        assertThat(tokens(table, 2)).contains("table:| 1 | 2 |")
        assertThat(tokens(table, 3)).isEmpty()
    }

    // ---- reveal ---------------------------------------------------------------------------------------------

    @Test fun `caret reveals its line and a whole code block`() {
        val text = "# one\n```\ncode\n```\ntwo"
        val doc = LiveMarkdownDocument.analyze(text)
        assertThat(doc.revealLines(2, 2)).isEqualTo(0..0)
        assertThat(doc.revealLines(text.indexOf("code"), text.indexOf("code"))).isEqualTo(1..3)
        assertThat(doc.revealLines(text.length, text.length)).isEqualTo(4..4)
        assertThat(doc.revealLines(0, text.length)).isEqualTo(0..4)
    }

    @Test fun `styled text keeps the source and hides markers off the caret line`() {
        val text = "# head\nplain **b**"
        val doc = LiveMarkdownDocument.analyze(text)
        val style = LiveMarkdownStyle(DarkColors)
        val caretOnSecond = LiveMarkdownView(0 until doc.lineCount, doc.revealLines(10, 10), TextRange(10))
        val styled = doc.styled(caretOnSecond, style)
        assertThat(styled.text).isEqualTo(text)
        val hashSpan = styled.spanStyles.first { it.start == 0 }
        assertThat(hashSpan.item).isEqualTo(style.hidden)
        val starSpan = styled.spanStyles.first { it.start == text.indexOf("**") && it.end == text.indexOf("**") + 2 }
        assertThat(starSpan.item).isEqualTo(style.marker)
    }

    @Test fun `prefix is drawn unless the caret touches it`() {
        val text = "- [ ] task text"
        val doc = LiveMarkdownDocument.analyze(text)
        val inText = LiveMarkdownView(0..0, 0..0, TextRange(10))
        assertThat(doc.touchesPrefix(0, inText.selection)).isFalse()
        assertThat(doc.decorations(inText).isEmpty).isFalse()
        val atBox = LiveMarkdownView(0..0, 0..0, TextRange(4))
        assertThat(doc.touchesPrefix(0, atBox.selection)).isTrue()
        assertThat(doc.decorations(atBox).isEmpty).isTrue()
    }

    // ---- task toggle ----------------------------------------------------------------------------------------

    @Test fun `task toggle edits only the box character`() {
        val text = "- [ ] milk\n- [x] eggs\n"
        val doc = LiveMarkdownDocument.analyze(text)
        val first = doc.taskBoxAt(0)
        val second = doc.taskBoxAt(1)
        assertThat(MarkdownParser.withTaskChecked(text, first, true)).isEqualTo("- [x] milk\n- [x] eggs\n")
        assertThat(MarkdownParser.withTaskChecked(text, second, false)).isEqualTo("- [ ] milk\n- [ ] eggs\n")
    }

    // ---- incremental updates ------------------------------------------------------------------------------

    @Test fun `typing a heading restyles it`() {
        val doc = LiveMarkdownDocument.analyze("#")
        assertThat(doc.line(0).kind).isEqualTo(LiveLineKind.HEADING)
        doc.update("#h")
        assertThat(doc.line(0).kind).isEqualTo(LiveLineKind.PARAGRAPH)
        doc.update("# h")
        assertThat(doc.line(0).kind).isEqualTo(LiveLineKind.HEADING)
        assertSameAsFresh(doc)
    }

    @Test fun `opening and closing a fence restyles the lines below`() {
        val doc = LiveMarkdownDocument.analyze("a\nb **c**\nd")
        doc.update("```\na\nb **c**\nd")
        assertThat(doc.line(2).kind).isEqualTo(LiveLineKind.CODE)
        assertSameAsFresh(doc)
        doc.update("```\na\n```\nb **c**\nd")
        assertThat(doc.line(3).kind).isEqualTo(LiveLineKind.PARAGRAPH)
        assertSameAsFresh(doc)
    }

    @Test fun `a delimiter row typed under a line turns it into a table header`() {
        val doc = LiveMarkdownDocument.analyze("| a | b |\n")
        doc.update("| a | b |\n|---|---|")
        assertThat(doc.line(0).kind).isEqualTo(LiveLineKind.TABLE)
        assertSameAsFresh(doc)
    }

    @Test fun `random edits match a fresh analysis`() {
        val pieces = listOf("# ", "**", "*", "_", "`", "```\n", "\n", "- ", "- [ ] ", "> ", "|", "---\n", "[a](b)", "x", " ", "1. ", "===\n", "~~")
        val random = Random(42)
        var text = "start\n"
        val doc = LiveMarkdownDocument.analyze(text)
        repeat(1500) {
            val at = random.nextInt(text.length + 1)
            text = if (random.nextInt(4) == 0 && text.isNotEmpty()) {
                val end = (at + random.nextInt(1, 6)).coerceAtMost(text.length)
                text.removeRange(at.coerceAtMost(end), end)
            } else {
                text.substring(0, at) + pieces[random.nextInt(pieces.size)] + text.substring(at)
            }
            assertThat(doc.update(text)).isTrue()
            assertSameAsFresh(doc)
        }
    }

    @Test fun `a long cascade stops early and reports it`() {
        val text = "x\n" + "line **b**\n".repeat(50)
        val doc = LiveMarkdownDocument.analyze(text)
        assertThat(doc.update("```\n$text", maxCascade = 10)).isFalse()
        assertThat(doc.text).isEqualTo("```\n$text")
    }

    // ---- windowing and size -------------------------------------------------------------------------------

    @Test fun `window covers everything when it fits and is bounded otherwise`() {
        val small = LiveMarkdownDocument.analyze("**a**\n**b**\n")
        assertThat(small.window(0, 100)).isEqualTo(0 until small.lineCount)
        val big = LiveMarkdownDocument.analyze("**a** *b*\n".repeat(1000))
        val w = big.window(500, 100)
        assertThat(w).contains(500)
        val used = w.sumOf { big.line(it).tokenCount }
        assertThat(used).isAtMost(110)
    }

    @Test fun `one megabyte analyses and types quickly`() {
        val block = "# Heading\n\nSome **bold** and *italic* text with `code` and a [link](https://x.org).\n" +
            "- [ ] a task\n- item\n> quote\n\n```\ncode line\n```\n| a | b |\n|---|---|\n| 1 | 2 |\n\n"
        val text = buildString { while (length < 1_000_000) append(block) }
        val t0 = System.nanoTime()
        val doc = LiveMarkdownDocument.analyze(text)
        val analyzeMs = (System.nanoTime() - t0) / 1_000_000
        var current = text
        val t1 = System.nanoTime()
        repeat(100) { i ->
            val at = current.length / 2 + i
            current = current.substring(0, at) + "x" + current.substring(at)
            doc.update(current)
        }
        val perKeyMs = (System.nanoTime() - t1) / 1_000_000.0 / 100
        val window = doc.window(doc.lineAt(current.length / 2), 600)
        val t2 = System.nanoTime()
        val styled = doc.styled(LiveMarkdownView(window, IntRange.EMPTY, null), LiveMarkdownStyle(DarkColors))
        val styleMs = (System.nanoTime() - t2) / 1_000_000.0
        println("live markdown 1MB: analyze ${analyzeMs}ms, update ${perKeyMs}ms/key, style ${styleMs}ms, ${styled.spanStyles.size} spans")
        assertThat(styled.text).isEqualTo(current)
        // Generous bounds for a slow CI machine; on a desktop these are ~10x lower.
        assertThat(analyzeMs).isLessThan(5_000)
        assertThat(perKeyMs).isLessThan(50.0)
        assertThat(styled.spanStyles.size).isAtMost(700)
    }
}

package org.samesame.notes.core.export.text

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Self-contained HTML export of Markdown source using CommonMark + GFM extensions.
 * Injects responsive CSS, tables, code blocks, task lists, and light/dark theme styling.
 */
object MarkdownHtmlExport {

    private val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create(),
        AutolinkExtension.create(),
    )

    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder().extensions(extensions).build()

    fun render(markdown: String, title: String, darkTheme: Boolean = false): String {
        val document = parser.parse(markdown)
        val renderedContent = renderer.render(document)

        val displayTitle = title.trim()
        val docTitle = displayTitle.ifEmpty { "Note" }

        val b = StringBuilder()
        b.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
        b.append("<meta charset=\"utf-8\">\n")
        b.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        b.append("<title>").append(escapeHtml(docTitle)).append("</title>\n")
        b.append("<style>\n")
        b.append(css(darkTheme))
        b.append("\n</style>\n</head>\n<body>\n")

        if (displayTitle.isNotEmpty() && !markdown.trimStart().startsWith("#")) {
            b.append("<h1>").append(escapeHtml(displayTitle)).append("</h1>\n")
        }

        b.append(renderedContent)
        b.append("</body>\n</html>\n")
        return b.toString()
    }

    private fun escapeHtml(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun css(darkTheme: Boolean): String {
        val lightVars = """
            --bg-color: #ffffff;
            --text-color: #1f2328;
            --border-color: #d0d7de;
            --link-color: #0969da;
            --table-border: #d0d7de;
            --table-th-bg: #f6f8fa;
            --table-even-bg: #fdfdfd;
            --code-bg: #f6f8fa;
            --inline-code-bg: rgba(175, 184, 193, 0.2);
            --quote-color: #656d76;
            --quote-border: #d0d7de;
        """.trimIndent()

        val darkVars = """
            --bg-color: #0d1117;
            --text-color: #e6edf3;
            --border-color: #30363d;
            --link-color: #2f81f7;
            --table-border: #30363d;
            --table-th-bg: #161b22;
            --table-even-bg: #11151c;
            --code-bg: #161b22;
            --inline-code-bg: rgba(110, 118, 129, 0.4);
            --quote-color: #848d97;
            --quote-border: #30363d;
        """.trimIndent()

        val defaultVars = if (darkTheme) darkVars else lightVars
        val mediaQuery = if (!darkTheme) {
            """
            @media (prefers-color-scheme: dark) {
                :root {
                    $darkVars
                }
            }
            """.trimIndent()
        } else ""

        return """
            :root {
                $defaultVars
            }
            $mediaQuery
            body {
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                font-size: 16px;
                line-height: 1.6;
                max-width: 48em;
                margin: 2em auto;
                padding: 0 1.5em;
                color: var(--text-color);
                background-color: var(--bg-color);
            }
            h1, h2, h3, h4, h5, h6 {
                line-height: 1.25;
                margin-top: 1.5em;
                margin-bottom: 0.5em;
                font-weight: 600;
            }
            h1 { font-size: 2em; border-bottom: 1px solid var(--border-color); padding-bottom: 0.3em; }
            h2 { font-size: 1.5em; border-bottom: 1px solid var(--border-color); padding-bottom: 0.3em; }
            h3 { font-size: 1.25em; }
            a { color: var(--link-color); text-decoration: none; }
            a:hover { text-decoration: underline; }
            table {
                border-collapse: collapse;
                width: 100%;
                margin: 1.5em 0;
            }
            th, td {
                border: 1px solid var(--table-border);
                padding: 8px 14px;
                text-align: left;
            }
            th {
                background-color: var(--table-th-bg);
                font-weight: 600;
            }
            tr:nth-child(even) {
                background-color: var(--table-even-bg);
            }
            pre {
                background-color: var(--code-bg);
                padding: 14px;
                border-radius: 6px;
                overflow-x: auto;
                font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace;
                font-size: 85%;
                line-height: 1.45;
            }
            code {
                font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace;
                font-size: 85%;
                padding: 0.2em 0.4em;
                border-radius: 4px;
                background-color: var(--inline-code-bg);
            }
            pre code {
                background-color: transparent;
                padding: 0;
            }
            blockquote {
                margin: 1em 0;
                padding: 0 1em;
                color: var(--quote-color);
                border-left: 0.25em solid var(--quote-border);
            }
            ul, ol {
                margin: 0.5em 0;
                padding-left: 2em;
            }
            li {
                margin: 0.25em 0;
            }
            li.task-list-item {
                list-style-type: none;
            }
            ul.task-list {
                list-style-type: none;
                padding-left: 0;
            }
            input[type="checkbox"] {
                margin: 0 0.5em 0.2em 0;
                vertical-align: middle;
            }
            img {
                max-width: 100%;
                height: auto;
            }
            hr {
                border: 0;
                border-top: 1px solid var(--border-color);
                margin: 1.5em 0;
            }
        """.trimIndent()
    }
}

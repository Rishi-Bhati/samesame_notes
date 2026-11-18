package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.export.text.MarkdownHtmlExport

class MarkdownHtmlExportTest {

    @Test
    fun testHtmlRendering() {
        val md = """
            # Document Title

            Here is a paragraph with **bold** and *italic* text.

            | Column A | Column B |
            |---|---|
            | Data 1 | Data 2 |

            - [ ] First task
            - [x] Second task

            ```kotlin
            val answer = 42
            ```

            > Inspiring quote
        """.trimIndent()

        // Light theme export
        val lightHtml = MarkdownHtmlExport.render(md, "Document Title", darkTheme = false)
        assertThat(lightHtml).contains("<!DOCTYPE html>")
        assertThat(lightHtml).contains("<title>Document Title</title>")
        assertThat(lightHtml).contains("<table>")
        assertThat(lightHtml).contains("<th>Column A</th>")
        assertThat(lightHtml).contains("<td>Data 1</td>")
        assertThat(lightHtml).contains("<input type=\"checkbox\"")
        assertThat(lightHtml).contains("<pre><code")
        assertThat(lightHtml).contains("val answer = 42")
        assertThat(lightHtml).contains("<blockquote>")
        assertThat(lightHtml).contains("@media (prefers-color-scheme: dark)")
        assertThat(lightHtml).contains("--bg-color: #ffffff;")

        // Dark theme export
        val darkHtml = MarkdownHtmlExport.render(md, "Document Title", darkTheme = true)
        assertThat(darkHtml).contains("--bg-color: #0d1117;")
        assertThat(darkHtml).contains("--text-color: #e6edf3;")
    }
}

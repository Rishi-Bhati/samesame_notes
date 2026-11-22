package org.samesame.notes.feature.textdoc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownDetectionTest {

    @Test
    fun detectsMarkdownByExtension() {
        assertThat(MarkdownDetection.isMarkdown("notes.md")).isTrue()
        assertThat(MarkdownDetection.isMarkdown("DOCUMENT.MARKDOWN")).isTrue()
        assertThat(MarkdownDetection.isMarkdown("readme.mdown")).isTrue()
        assertThat(MarkdownDetection.isMarkdown("file.MD")).isTrue()
        assertThat(MarkdownDetection.isMarkdown("  spaced.markdown  ")).isTrue()

        assertThat(MarkdownDetection.isMarkdown("notes.txt")).isFalse()
        assertThat(MarkdownDetection.isMarkdown("document.html")).isFalse()
        assertThat(MarkdownDetection.isMarkdown("md")).isFalse()
        assertThat(MarkdownDetection.isMarkdown(null)).isFalse()
    }

    @Test
    fun detectsMarkdownByMimeType() {
        assertThat(MarkdownDetection.isMarkdown("untitled", "text/markdown")).isTrue()
        assertThat(MarkdownDetection.isMarkdown("untitled", "text/x-markdown")).isTrue()
        assertThat(MarkdownDetection.isMarkdown(null, "TEXT/MARKDOWN")).isTrue()
        assertThat(MarkdownDetection.isMarkdown(null, "text/x-markdown")).isTrue()

        assertThat(MarkdownDetection.isMarkdown("untitled", "text/plain")).isFalse()
        assertThat(MarkdownDetection.isMarkdown("untitled", "text/html")).isFalse()
        assertThat(MarkdownDetection.isMarkdown(null, null)).isFalse()
    }

    @Test
    fun aMarkdownNameWinsOverAGenericOrWrongMimeType() {
        assertThat(MarkdownDetection.isMarkdown("test.md", "text/plain")).isTrue()
        assertThat(MarkdownDetection.isMarkdown("test.md", "application/octet-stream")).isTrue()
        assertThat(MarkdownDetection.isMarkdown("test.MD", "text/html")).isTrue()
        assertThat(MarkdownDetection.isMarkdown("test.txt", "text/plain")).isFalse()
        assertThat(MarkdownDetection.isMarkdown("text/markdown", "text/plain")).isFalse()
        assertThat(MarkdownDetection.isMarkdown(null, "text/markdown; charset=utf-8")).isTrue()
    }
}

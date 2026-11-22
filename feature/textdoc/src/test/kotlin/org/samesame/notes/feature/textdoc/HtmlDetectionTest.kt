package org.samesame.notes.feature.textdoc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HtmlDetectionTest {

    @Test
    fun detectsHtmlByExtension() {
        assertThat(HtmlDetection.isHtml("index.html")).isTrue()
        assertThat(HtmlDetection.isHtml("PAGE.HTM")).isTrue()
        assertThat(HtmlDetection.isHtml(" a.xhtml ")).isTrue()
        assertThat(HtmlDetection.isHtml("notes.md", "text/plain")).isFalse()
        assertThat(HtmlDetection.isHtml("notes.txt")).isFalse()
        assertThat(HtmlDetection.isHtml("html")).isFalse()
        assertThat(HtmlDetection.isHtml(null)).isFalse()
    }

    @Test
    fun detectsHtmlByMimeType() {
        assertThat(HtmlDetection.isHtml(null, "text/html")).isTrue()
        assertThat(HtmlDetection.isHtml("x", "text/html; charset=utf-8")).isTrue()
        assertThat(HtmlDetection.isHtml(null, "Application/XHTML+xml")).isTrue()
        assertThat(HtmlDetection.isHtml("x", "text/plain")).isFalse()
        assertThat(HtmlDetection.isHtml("x", "text/markdown")).isFalse()
        assertThat(HtmlDetection.isHtml(null, null)).isFalse()
    }
}

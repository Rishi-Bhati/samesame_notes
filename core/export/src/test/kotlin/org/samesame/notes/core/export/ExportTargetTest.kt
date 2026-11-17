package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExportTargetTest {
    @Test fun withFilesTurnsMarkdownAndHtmlIntoZip() {
        assertThat(expectedTarget(ExportFormat.MARKDOWN, ExportOptions(linkFilesAsZip = true), false, 3).extension).isEqualTo("zip")
        assertThat(expectedTarget(ExportFormat.HTML, ExportOptions(linkFilesAsZip = true), false, 3).extension).isEqualTo("zip")
        assertThat(expectedTarget(ExportFormat.MARKDOWN, ExportOptions(linkFilesAsZip = true), false, 3).mimeType).isEqualTo("application/zip")
    }

    @Test fun withoutTheFlagTheTextFormatsKeepTheirOwnExtensions() {
        assertThat(expectedTarget(ExportFormat.MARKDOWN, ExportOptions(), false, 3).extension).isEqualTo("md")
        assertThat(expectedTarget(ExportFormat.HTML, ExportOptions(), false, 3).mimeType).isEqualTo("text/html")
        assertThat(expectedTarget(ExportFormat.MARKDOWN, ExportOptions(markdownAsZip = true), false, 3).extension).isEqualTo("zip")
    }

    @Test fun otherFormatsIgnoreTheFlag() {
        assertThat(expectedTarget(ExportFormat.PDF, ExportOptions(linkFilesAsZip = true), false, 3).extension).isEqualTo("pdf")
        assertThat(expectedTarget(ExportFormat.SSNOTE, ExportOptions(linkFilesAsZip = true), false, 1).extension).isEqualTo("ssnote")
        assertThat(expectedTarget(ExportFormat.TXT, ExportOptions(linkFilesAsZip = true), false, 3).extension).isEqualTo("txt")
    }
}

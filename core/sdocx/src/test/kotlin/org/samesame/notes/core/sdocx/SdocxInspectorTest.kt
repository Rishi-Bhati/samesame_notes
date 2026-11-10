package org.samesame.notes.core.sdocx

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.sdocx.map.SdocxMapper
import java.io.File
import java.nio.file.Files

/**
 * Runs the independent inspector from `research/sdocx_inspect.py` over files this module wrote.
 *
 * The inspector is not part of the repo, so the tests are skipped unless
 * `SAMESAME_SDOCX_INSPECTOR` points at it. They assert the inspector's exit code and that it
 * reports `0 failed checks`, which covers every hash, offset and exact-consumption check it makes.
 */
class SdocxInspectorTest {

    private fun inspector(): String? {
        val path = System.getenv("SAMESAME_SDOCX_INSPECTOR") ?: return null
        return path.takeIf { File(it).isFile }
    }

    private fun inspect(bytes: ByteArray, name: String): String {
        val dir = Files.createTempDirectory("sdocx-inspect").toFile()
        try {
            val file = File(dir, name).apply { writeBytes(bytes) }
            val process = ProcessBuilder("python3", "-I", inspector()!!, file.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            assertThat(output).contains("0 failed checks")
            assertThat(exit).isEqualTo(0)
            return output
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `inspector accepts the no-edit rich export`() {
        assumeTrue("SAMESAME_SDOCX_INSPECTOR is not set", inspector() != null)
        val bytes = SdocxWriter().writeToBytes(SdocxFixtures.richArchive())
        inspect(bytes, "rich.sdocx")
    }

    @Test
    fun `inspector accepts an edited rich export`() {
        assumeTrue("SAMESAME_SDOCX_INSPECTOR is not set", inspector() != null)
        val archive = SdocxReader().read(SdocxWriter().writeToBytes(SdocxFixtures.richArchive()))
        val document = SdocxMapper.toNoteDocument(archive, "n")
        val edited = SdocxMapper.applyDocumentEdits(
            archive,
            document.copy(
                title = "Edited title",
                body = RichText.of(document.body.text + "!"),
            ),
            nowUs = SdocxFixtures.MODIFIED_US + 1_000L,
        )
        inspect(SdocxWriter().writeToBytes(edited), "edited.sdocx")
    }

    @Test
    fun `inspector accepts an object edit and a page image fallback`() {
        assumeTrue("SAMESAME_SDOCX_INSPECTOR is not set", inspector() != null)
        val archive = SdocxReader().read(SdocxWriter().writeToBytes(SdocxFixtures.richArchive()))
        val page = archive.pages[1]
        val edited = SdocxPageImageFallback.apply(
            archive,
            pageImages = mapOf(page.pageId to byteArrayOf(1, 2, 3, 4)),
            newUuid = { "12121212-1212-1212-1212-121212121212" },
            nowUs = { SdocxFixtures.MODIFIED_US + 2_000L },
        )
        inspect(SdocxWriter().writeToBytes(edited), "fallback.sdocx")
    }
}

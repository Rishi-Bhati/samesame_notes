package org.samesame.notes.core.sdocx

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.sdocx.map.SdocxMapper
import java.io.File

/**
 * Writes this module's output to `SAMESAME_SDOCX_DUMP_DIR` so it can be compared with files from the
 * reference app by an external tool (`sdocx_inspect.py --diff`). Skipped without the variable.
 */
class SdocxDumpTest {

    @Test
    fun `dump native and re-exported files`() {
        val dir = System.getenv("SAMESAME_SDOCX_DUMP_DIR")?.let(::File)
        assumeTrue("SAMESAME_SDOCX_DUMP_DIR is not set", dir != null)
        dir!!.mkdirs()

        val native = SdocxNativeInk.export(
            NoteDocument(
                id = "0d4e2c1a-5b6f-4a7e-9c3d-2f1e0a9b8c7d",
                title = "native",
                pages = listOf(PageSpec("3f2a1b0c-9d8e-4f7a-b6c5-d4e3f2a1b0c9")),
            ),
            mapOf(
                "3f2a1b0c-9d8e-4f7a-b6c5-d4e3f2a1b0c9" to listOf(
                    SdocxInkSample(1L, "samesame/Fountain/pressure", 0xFF335FFFL, 7.65f, SdocxStrokeCodec.TOOL_SPEN, floatArrayOf(540f, 252f)),
                ),
            ),
            nowUs = 1_794_250_000_000_000L,
        )
        File(dir, "native-dot.sdocx").writeBytes(SdocxWriter().writeToBytes(native))

        for (name in SdocxTestFixtures.NAMES) {
            val bytes = SdocxTestFixtures.bytes(name)
            val archive = SdocxReader().read(bytes)
            File(dir, "$name-rezip.sdocx").writeBytes(SdocxWriter().writeToBytes(archive))
            val document = SdocxMapper.toNoteDocument(archive, "n")
            val edited = SdocxMapper.applyDocumentEdits(
                archive,
                document.copy(body = RichText.of(document.body.text + "x")),
                nowUs = archive.note.modifiedTime + 60_000_000L,
            )
            File(dir, "$name-edited.sdocx").writeBytes(SdocxWriter().writeToBytes(edited))
        }
    }
}

package org.samesame.notes.core.sdocx

import java.io.File

/** The device fixtures in `src/test/resources/sdocx` (files written by the reference app). */
object SdocxTestFixtures {

    val NAMES = listOf(
        "f01-dot", "f02-line-h", "f03-line-v", "f04-two-strokes", "f05-pens", "f06-colours", "f07-sizes",
        "f08-highlighter", "f09-text-styled", "f10-image", "f12-pages", "f14-eraser", "f15-slow", "f16-diagonal",
    )

    fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("sdocx/$name.sdocx")) { "missing fixture $name.sdocx" }
            .use { it.readBytes() }

    fun archive(name: String): SdocxArchive = SdocxReader().read(bytes(name))

    /**
     * Fixtures too large for the repository (`f11-voice`), read from `SAMESAME_SDOCX_FIXTURE_DIR` when
     * that is set; empty otherwise.
     */
    fun external(): List<Pair<String, ByteArray>> {
        val dir = System.getenv("SAMESAME_SDOCX_FIXTURE_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".sdocx") && f.name.removeSuffix(".sdocx") !in NAMES }
            .orEmpty().sortedBy { it.name }.map { it.name.removeSuffix(".sdocx") to it.readBytes() }
    }
}

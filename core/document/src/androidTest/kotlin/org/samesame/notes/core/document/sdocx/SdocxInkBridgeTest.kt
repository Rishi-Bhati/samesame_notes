package org.samesame.notes.core.document.sdocx

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.ink.PenBrushes
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.PageLayerCodec
import org.samesame.notes.core.ink.tools.StrokeTransforms
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.sdocx.SdocxArchive
import org.samesame.notes.core.sdocx.SdocxInk
import org.samesame.notes.core.sdocx.SdocxInkSample
import org.samesame.notes.core.sdocx.SdocxNativeInk
import org.samesame.notes.core.sdocx.SdocxReader
import org.samesame.notes.core.sdocx.SdocxStrokeCodec
import org.samesame.notes.core.sdocx.SdocxStrokeMerge
import org.samesame.notes.core.sdocx.SdocxWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Instrumented bridge tests (androidx.ink needs its native library, so they cannot run on the JVM).
 * Build with `:core:document:compileDebugAndroidTestKotlin`; they are not run by the build gate.
 *
 * What only a device can prove is here: that ink accepts the surfaced strokes, and that a page
 * saved through the ink layer codec and read back still compares as unchanged, so an export of an
 * untouched page keeps the source bytes.
 */
@RunWith(AndroidJUnit4::class)
class SdocxInkBridgeTest {

    private val pageId = "3f2a1b0c-9d8e-4f7a-b6c5-d4e3f2a1b0c9"

    private val keys = listOf(
        "samesame/Fountain/pressure", "samesame/Calligraphy/pressure", "samesame/Pen/pressure",
        "samesame/Pencil/pressure", "samesame/Brush/pressure", "samesame/Highlighter/fixed",
    )

    /** A note with one stroke per pen, read back the way an imported file is. */
    private fun archive(): SdocxArchive {
        val ink = keys.mapIndexed { i, key ->
            SdocxInkSample(
                i + 1L, key, 0xFF102030L + i, 4f + i, SdocxStrokeCodec.TOOL_SPEN,
                floatArrayOf(100f, 100f + 120 * i, 420.5f, 130f + 120 * i, 900f, 80f + 120 * i, 900f, 80f + 120 * i),
            )
        }
        val built = SdocxNativeInk.export(
            NoteDocument(id = "n", title = "ink", pages = listOf(PageSpec(pageId))), mapOf(pageId to ink), 1_800_000_000_000_000L,
        )
        return SdocxReader().read(SdocxWriter().writeToBytes(built))
    }

    private fun stored(content: PageContent): PageContent {
        val out = ByteArrayOutputStream()
        PageLayerCodec.encode(content, out)
        return PageContent(content.pageId, PageLayerCodec.decode(ByteArrayInputStream(out.toByteArray())), 0)
    }

    @Test
    fun surfacedStrokesHaveTheirPenColourSizeAndPoints() {
        val archive = archive()
        val elements = SdocxInkBridge.pageElements(archive, archive.pages[0])
        assertThat(elements).hasSize(keys.size)
        elements.forEachIndexed { i, element ->
            val stroke = (element as InkElement).stroke
            assertThat(stroke.brush.family.developerComment).isEqualTo(PenBrushes.familyForKey(keys[i]).developerComment)
            assertThat(stroke.brush.colorIntArgb).isEqualTo((0xFF102030L + i).toInt())
            assertThat(stroke.brush.size).isEqualTo(4f + i)
            assertThat(stroke.inputs.size).isEqualTo(4)
        }
    }

    @Test
    fun sampleSurvivesTheInkObjects() {
        val sample = SdocxInkSample(7L, "samesame/Pencil/pressure", 0xA0252525L, 5.05f, SdocxStrokeCodec.TOOL_SPEN, floatArrayOf(1f, 2f, 30.5f, 40.25f))
        val back = SdocxInkBridge.sample(InkElement(org.samesame.notes.core.ink.page.ElementId(7L), SdocxInkBridge.stroke(sample)))
        assertThat(back.penKey).isEqualTo(sample.penKey)
        assertThat(back.argb).isEqualTo(sample.argb)
        assertThat(back.size).isEqualTo(sample.size)
        assertThat(back.xy.toList()).isEqualTo(sample.xy.toList())
    }

    @Test
    fun pageSavedAndReadBackIsStillUnchangedForExport() {
        val archive = archive()
        val page = archive.pages[0]
        val content = stored(SdocxInkBridge.pageContent(archive, page))
        val strokes = SdocxInkBridge.pageStrokes(archive, page, content)
        assertThat(SdocxStrokeMerge.apply(archive, mapOf(page.pageId to strokes), 1_800_000_000_000_001L)).isSameInstanceAs(archive)
    }

    @Test
    fun movedAndDeletedStrokesReachTheExport() {
        val archive = archive()
        val page = archive.pages[0]
        val shown = SdocxInkBridge.pageContent(archive, page)
        val first = shown.elements[0].id
        val moved = StrokeTransforms.apply(shown.elements.dropLast(1), setOf(first), StrokeTransforms.translate(25f, -10f))
        val content = stored(PageContent(page.pageId, moved, 1))
        val merged = SdocxStrokeMerge.apply(archive, mapOf(page.pageId to SdocxInkBridge.pageStrokes(archive, page, content)), 1_800_000_000_000_001L)
        val reread = SdocxReader().read(SdocxWriter().writeToBytes(merged))
        val before = SdocxInk.surface(archive, page)
        val after = SdocxInk.surface(reread, reread.pages[0])
        assertThat(after).hasSize(before.size - 1)
        assertThat(after[0].xy[0]).isWithin(0.05f).of(before[0].xy[0] + 25f)
        assertThat(after[0].xy[1]).isWithin(0.05f).of(before[0].xy[1] - 10f)
        for (i in 1 until after.size) assertThat(after[i].xy.toList()).isEqualTo(before[i].xy.toList())
    }
}

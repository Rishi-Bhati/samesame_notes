package org.samesame.notes.core.document.sdocx

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import org.samesame.notes.core.ink.PenBrushes
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.sdocx.SdocxArchive
import org.samesame.notes.core.sdocx.SdocxInk
import org.samesame.notes.core.sdocx.SdocxInkSample
import org.samesame.notes.core.sdocx.SdocxPageStroke
import org.samesame.notes.core.sdocx.SdocxStrokeCodec
import org.samesame.notes.core.sdocx.record.PageRecord

/**
 * Converts between the pure-JVM handwriting model of `core:sdocx` ([SdocxInkSample]) and the
 * androidx.ink strokes the editor works with. Everything that decides what is shown and what an
 * export writes lives in [SdocxInk]; this object only builds and reads ink objects, which needs the
 * ink native library.
 *
 * The file's per-point data is not understood (objects spec §4.3.2), so a surfaced stroke gets
 * neutral pressure and evenly spaced times; those are render-only and are never written back.
 */
object SdocxInkBridge {

    /** The handwriting of an imported page as editor elements. */
    fun pageElements(archive: SdocxArchive, page: PageRecord): List<PageElement> =
        SdocxInk.surface(archive, page).mapNotNull { sample ->
            // A stroke the ink library refuses is left out here and stays untouched in the archive.
            runCatching { InkElement(ElementId(sample.id), stroke(sample)) }.getOrNull()
        }

    fun pageContent(archive: SdocxArchive, page: PageRecord): PageContent =
        PageContent(page.pageId, pageElements(archive, page), 0)

    /**
     * The strokes of an imported [page] as the editor has them in [content], for the export merge.
     * Strokes the bridge could not show count as unchanged, never as deleted.
     */
    fun pageStrokes(archive: SdocxArchive, page: PageRecord, content: PageContent): List<SdocxPageStroke> {
        val shown = pageElements(archive, page).mapTo(HashSet()) { it.id.value }
        return SdocxInk.pageStrokes(archive, page, samples(content), shown)
    }

    /** The ink of [content] in z-order; other elements are skipped. */
    fun samples(content: PageContent): List<SdocxInkSample> =
        content.elements.mapNotNull { element -> (element as? InkElement)?.let { sample(it) } }

    fun sample(element: InkElement): SdocxInkSample {
        val stroke = element.stroke
        val inputs = stroke.inputs
        val xy = FloatArray(inputs.size * 2)
        var tool = SdocxStrokeCodec.TOOL_SPEN
        val input = androidx.ink.strokes.StrokeInput()
        for (i in 0 until inputs.size) {
            inputs.populate(i, input)
            xy[i * 2] = input.x
            xy[i * 2 + 1] = input.y
            if (i == 0) tool = toolOf(input.toolType)
        }
        return SdocxInkSample(
            id = element.id.value,
            penKey = stroke.brush.family.developerComment,
            argb = stroke.brush.colorIntArgb.toLong() and 0xFFFFFFFFL,
            size = stroke.brush.size,
            toolType = tool,
            xy = xy,
        )
    }

    fun stroke(sample: SdocxInkSample): Stroke {
        val tool = when (sample.toolType) {
            SdocxStrokeCodec.TOOL_FINGER -> InputToolType.TOUCH
            SdocxStrokeCodec.TOOL_MOUSE -> InputToolType.MOUSE
            else -> InputToolType.STYLUS
        }
        val inputs = MutableStrokeInputBatch()
        for (i in 0 until sample.xy.size / 2) {
            inputs.add(tool, x = sample.xy[i * 2], y = sample.xy[i * 2 + 1], elapsedTimeMillis = i * INPUT_INTERVAL_MS, pressure = 1f)
        }
        return Stroke(PenBrushes.brushForKey(sample.penKey, sample.argb.toInt(), sample.size), inputs)
    }

    private fun toolOf(tool: InputToolType): Int = when (tool) {
        InputToolType.TOUCH -> SdocxStrokeCodec.TOOL_FINGER
        InputToolType.MOUSE -> SdocxStrokeCodec.TOOL_MOUSE
        else -> SdocxStrokeCodec.TOOL_SPEN
    }

    /** Render-only spacing for the missing timestamps (16 ms ~ one stylus sample). */
    private const val INPUT_INTERVAL_MS = 16L
}

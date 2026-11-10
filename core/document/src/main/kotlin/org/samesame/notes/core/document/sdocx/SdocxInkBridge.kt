package org.samesame.notes.core.document.sdocx

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import org.samesame.notes.core.ink.PenBrushes
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.model.PenSettings
import org.samesame.notes.core.model.PenType
import org.samesame.notes.core.sdocx.SdocxArchive
import org.samesame.notes.core.sdocx.SdocxStroke
import org.samesame.notes.core.sdocx.SdocxStrokeCodec
import org.samesame.notes.core.sdocx.map.SdocxMapper
import org.samesame.notes.core.sdocx.record.ObjectEntry
import org.samesame.notes.core.sdocx.record.PageRecord
import java.util.UUID

/**
 * Bridges the pure-JVM `.sdocx` stroke model onto the androidx.ink model used by the editor
 * (objects spec §4.3, §14). This is the view-side adapter: strokes decoded from a page become
 * [InkElement]s the composer can render and edit. The raw sdocx records remain in the side channel,
 * so an unedited export still uses their original bytes.
 *
 * The payload has no per-point pressure/tilt/timestamp data in the verified layout (§4.3.2), so the
 * editor gets neutral pressure and evenly spaced times; these are render-only values and are never
 * written back to `.sdocx`.
 */
object SdocxInkBridge {

    /**
     * Converts one page's object entries to layered page content. Only type-1 strokes are surfaced;
     * every other object type stays raw in the side channel for a lossless re-export.
     */
    fun pageContent(
        pageId: String,
        objects: List<ObjectEntry>,
        penName: String?,
        scale: Float,
    ): PageContent {
        val elements = ArrayList<PageElement>()
        var index = 0
        fun visit(entry: ObjectEntry) {
            if (entry.objectType == SdocxStrokeCodec.OBJECT_TYPE_STROKE) {
                val stroke = SdocxStrokeCodec.decode(entry)
                if (stroke != null) {
                    elements += InkElement(elementId(pageId, entry, index++), inkStroke(stroke, penName, scale))
                }
            }
            entry.children.forEach(::visit)
        }
        objects.forEach(::visit)
        return PageContent(pageId, elements, 0)
    }

    /** Page content for [page] of [archive], scaled from the sdocx page width to SameSame page units. */
    fun pageContent(archive: SdocxArchive, page: PageRecord): PageContent = pageContent(
        pageId = page.pageId,
        objects = page.layers.flatMap { it.objects },
        penName = notePenName(archive),
        scale = SdocxMapper.geometryScale(archive),
    )

    /** The note-level pen name used to pick a brush (the stroke payload has none; objects spec §4.1). */
    fun notePenName(archive: SdocxArchive): String? =
        archive.note.properties.pen?.penName
            ?: archive.note.properties.legacyPen?.penName

    /**
     * Maps one decoded stroke to an androidx.ink stroke. Coordinates and pen size are scaled from
     * sdocx page units to SameSame page units; colour and tool type come from the payload.
     */
    fun inkStroke(stroke: SdocxStroke, penName: String?, scale: Float): Stroke {
        val type = penTypeOf(penName)
        val settings = PenSettings(
            type = type,
            color = stroke.colour,
            size = stroke.penSize * scale,
            pressureSensitive = type != PenType.Highlighter,
        )
        val tool = inputToolOf(stroke.toolType)
        val inputs = MutableStrokeInputBatch()
        stroke.points.forEachIndexed { index, point ->
            inputs.add(
                tool,
                x = (point.x * scale).toFloat(),
                y = (point.y * scale).toFloat(),
                elapsedTimeMillis = index * INPUT_INTERVAL_MS,
                pressure = 1f,
            )
        }
        return Stroke(PenBrushes.brushFor(settings), inputs)
    }

    /** Reference pen class name suffix → SameSame pen family (objects spec §4.1.1 pen table). */
    fun penTypeOf(penName: String?): PenType {
        val name = penName.orEmpty()
        return when {
            name.isEmpty() -> PenType.Pen
            name.endsWith("FountainPen") -> PenType.Fountain
            name.endsWith("ObliquePen") || name.endsWith("CalligraphyPen") -> PenType.Calligraphy
            name.contains("InkPen") -> PenType.Pen
            name.contains("Pencil") -> PenType.Pencil
            name.contains("Marker") || name.contains("Highlighter") -> PenType.Highlighter
            name.contains("Brush") || name.contains("WaterColor") || name.contains("Oil") -> PenType.Brush
            else -> PenType.Pen
        }
    }

    /** sdocx tool enum (objects spec §4.3) → ink input tool type. */
    private fun inputToolOf(toolType: Int): InputToolType = when (toolType) {
        SdocxStrokeCodec.TOOL_FINGER -> InputToolType.TOUCH
        SdocxStrokeCodec.TOOL_MOUSE -> InputToolType.MOUSE
        else -> InputToolType.STYLUS
    }

    private fun elementId(pageId: String, entry: ObjectEntry, index: Int): ElementId {
        val seed = entry.uuid ?: "$pageId:$index"
        val value = UUID.nameUUIDFromBytes("sdocx:$seed".toByteArray(Charsets.UTF_8)).mostSignificantBits
        return ElementId(if (value == 0L) Long.MIN_VALUE else value)
    }

    /** Render-only spacing for the missing timestamps (16 ms ~ one stylus sample). */
    private const val INPUT_INTERVAL_MS = 16L
}

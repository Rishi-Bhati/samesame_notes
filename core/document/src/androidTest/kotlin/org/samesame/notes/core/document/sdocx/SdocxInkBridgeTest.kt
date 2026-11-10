package org.samesame.notes.core.document.sdocx

import androidx.ink.brush.InputToolType
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.model.PenType
import org.samesame.notes.core.sdocx.SdocxStroke
import org.samesame.notes.core.sdocx.SdocxStrokeCodec
import org.samesame.notes.core.sdocx.SdocxStrokePoint
import org.samesame.notes.core.sdocx.record.ObjectEntry

/**
 * Instrumented bridge tests (androidx.ink builds native meshes, so they cannot run on the JVM).
 * Build with `:core:document:assembleDebugAndroidTest`; they are not run by the build gate.
 */
@RunWith(AndroidJUnit4::class)
class SdocxInkBridgeTest {

    private fun strokeRecord(
        points: List<SdocxStrokePoint> = listOf(SdocxStrokePoint(10.0, 20.0), SdocxStrokePoint(30.0, 20.0)),
        colour: Long = 0xFF335FFFL,
        size: Float = 4f,
        toolType: Int = SdocxStrokeCodec.TOOL_SPEN,
        uuid: String = "11111111-1111-1111-1111-111111111111",
    ): ByteArray = SdocxStrokeCodec.newRecord(
        SdocxStroke(points, colour, size, toolType),
        uuid = uuid,
        modifiedTimeUs = 1_700_000_000_000_000L,
        ownerPageWidth = 1080,
        ownerPageHeight = 1527,
    )

    private fun entry(
        type: Int = SdocxStrokeCodec.OBJECT_TYPE_STROKE,
        toolType: Int = SdocxStrokeCodec.TOOL_SPEN,
        record: ByteArray = strokeRecord(toolType = toolType),
        uuid: String = "11111111-1111-1111-1111-111111111111",
        children: List<ObjectEntry> = emptyList(),
    ): ObjectEntry = ObjectEntry(
        objectType = type,
        childCount = children.size,
        record = record,
        originalHash = ByteArray(32),
        uuid = uuid,
        modifiedTime = 1_700_000_000_000_000L,
        children = children,
    )

    @Test
    fun fountainStrokeMapsToInkStroke() {
        val stroke = SdocxInkBridge.inkStroke(
            checkNotNull(SdocxStrokeCodec.decode(entry())),
            penName = "com.samsung.android.sdk.pen.pen.preload.FountainPen",
            scale = 0.5f,
        )
        assertThat(stroke.brush.family.developerComment).isEqualTo("samesame/Fountain/pressure")
        assertThat(stroke.brush.colorIntArgb).isEqualTo(0xFF335FFF.toInt())
        assertThat(stroke.brush.size).isWithin(1e-4f).of(2f)
        assertThat(stroke.inputs.size).isEqualTo(2)
        assertThat(stroke.inputs[0].x).isWithin(1e-4f).of(5f)
        assertThat(stroke.inputs[0].y).isWithin(1e-4f).of(10f)
        assertThat(stroke.inputs[1].x).isWithin(1e-4f).of(15f)
        assertThat(stroke.inputs[0].toolType).isEqualTo(InputToolType.STYLUS)
    }

    @Test
    fun penNamesMapToFamilies() {
        assertThat(SdocxInkBridge.penTypeOf(null)).isEqualTo(PenType.Pen)
        assertThat(SdocxInkBridge.penTypeOf("...preload.FountainPen")).isEqualTo(PenType.Fountain)
        assertThat(SdocxInkBridge.penTypeOf("...preload.ObliquePen")).isEqualTo(PenType.Calligraphy)
        assertThat(SdocxInkBridge.penTypeOf("...preload.InkPen2")).isEqualTo(PenType.Pen)
        assertThat(SdocxInkBridge.penTypeOf("...preload.Pencil2")).isEqualTo(PenType.Pencil)
        assertThat(SdocxInkBridge.penTypeOf("...preload.Marker3")).isEqualTo(PenType.Highlighter)
        assertThat(SdocxInkBridge.penTypeOf("...preload.BrushPen")).isEqualTo(PenType.Brush)
        assertThat(SdocxInkBridge.penTypeOf("com.example.Unknown")).isEqualTo(PenType.Pen)
    }

    @Test
    fun toolTypeAndPointsSurvive() {
        val finger = SdocxInkBridge.inkStroke(
            checkNotNull(SdocxStrokeCodec.decode(entry(toolType = SdocxStrokeCodec.TOOL_FINGER))),
            penName = "InkPen2",
            scale = 1f,
        )
        assertThat(finger.inputs[0].toolType).isEqualTo(InputToolType.TOUCH)
        val mouse = SdocxInkBridge.inkStroke(
            checkNotNull(SdocxStrokeCodec.decode(entry(toolType = SdocxStrokeCodec.TOOL_MOUSE))),
            penName = "InkPen2",
            scale = 1f,
        )
        assertThat(mouse.inputs[0].toolType).isEqualTo(InputToolType.MOUSE)
    }

    @Test
    fun pageContentSkipsNonStrokesAndFlattensChildren() {
        val child = entry(uuid = "22222222-2222-2222-2222-222222222222")
        val container = entry(type = 4, uuid = "33333333-3333-3333-3333-333333333333", children = listOf(child))
        val image = entry(type = 3, uuid = "44444444-4444-4444-4444-444444444444")
        val top = entry(uuid = "55555555-5555-5555-5555-555555555555")
        val content = SdocxInkBridge.pageContent("page-1", listOf(image, container, top), "InkPen2", 1f)
        assertThat(content.pageId).isEqualTo("page-1")
        assertThat(content.elements).hasSize(2)
        assertThat(content.elements).containsExactlyElementsIn(content.elements)
        assertThat(content.elements.all { it is InkElement }).isTrue()
        assertThat(content.elements.map { it.id.value }.toSet()).hasSize(2)
    }
}

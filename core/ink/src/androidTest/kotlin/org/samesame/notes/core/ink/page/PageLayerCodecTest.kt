package org.samesame.notes.core.ink.page

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.ink.PenBrushes
import org.samesame.notes.core.model.PenSettings
import org.samesame.notes.core.model.PenType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

// assets/v1_page.ink was produced by StrokeCodec.encode (v1) with Fountain/pressure, Calligraphy/fixed,
// Highlighter/pressure strokes of two inputs each.
@RunWith(AndroidJUnit4::class)
class PageLayerCodecTest {
    private fun stroke(type: PenType, pressure: Boolean, x: Float): Stroke {
        val inputs = MutableStrokeInputBatch()
            .add(InputToolType.STYLUS, x = x, y = 0f, elapsedTimeMillis = 0, pressure = 0.5f)
            .add(InputToolType.STYLUS, x = x + 10f, y = 12f, elapsedTimeMillis = 16, pressure = 0.7f)
        return Stroke(PenBrushes.brushFor(PenSettings(type, 0xFF1A73E8, 6f, pressure)), inputs)
    }

    private fun encode(elements: List<PageElement>): ByteArray =
        ByteArrayOutputStream().also { PageLayerCodec.encode(PageContent("p", elements, 1), it) }.toByteArray()

    @Test
    fun v2RoundTripInterleavedKeepsIdsAndOrder() {
        val obj = ObjectElement(ElementId(20), """{"k":"é"}""", box(1f, 2f, 30f, 40f))
        val elements = listOf(
            InkElement(ElementId(10), stroke(PenType.Fountain, true, 0f)),
            obj,
            InkElement(ElementId(30), stroke(PenType.Calligraphy, false, 50f)),
        )
        val decoded = PageLayerCodec.decode(ByteArrayInputStream(encode(elements)))
        assertThat(decoded.map { it.id.value }).containsExactly(10L, 20L, 30L).inOrder()
        val o = decoded[1] as ObjectElement
        assertThat(o.json).isEqualTo(obj.json)
        assertThat(o.bounds).isEqualTo(obj.bounds)
        val s0 = (decoded[0] as InkElement).stroke
        assertThat(s0.brush.family.developerComment).isEqualTo("samesame/Fountain/pressure")
        assertThat(s0.inputs.size).isEqualTo(2)
        assertThat(s0.brush.size).isEqualTo(6f)
        assertThat((decoded[2] as InkElement).stroke.brush.family.developerComment)
            .isEqualTo("samesame/Calligraphy/fixed")
    }

    @Test
    fun resolveFamilyIsInjected() {
        val keys = mutableListOf<String>()
        val bytes = encode(listOf(InkElement(ElementId(1), stroke(PenType.Pen, true, 0f))))
        PageLayerCodec.decode(ByteArrayInputStream(bytes)) { key -> keys += key; PenBrushes.familyForKey(key) }
        assertThat(keys).containsExactly("samesame/Pen/pressure")
    }

    @Test
    fun unknownTagsAreSkipped() {
        val good = encode(listOf(InkElement(ElementId(5), stroke(PenType.Pen, true, 0f))))
        // Rebuild: header with count 2, an unknown tag 99 record, then the stroke record copied from `good`.
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeInt(0x53534E4B); d.writeShort(2); d.writeShort(0); d.writeInt(2)
        d.writeByte(99); d.writeInt(3); d.write(byteArrayOf(1, 2, 3))
        d.write(good, 12, good.size - 12)
        val decoded = PageLayerCodec.decode(ByteArrayInputStream(out.toByteArray()))
        assertThat(decoded.map { it.id.value }).containsExactly(5L)
    }

    @Test
    fun newerVersionThrows() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeInt(0x53534E4B); writeShort(3); writeShort(0); writeInt(0) }
        assertThrows(IOException::class.java) { PageLayerCodec.decode(ByteArrayInputStream(out.toByteArray())) }
    }

    @Test
    fun v1FixtureDecodesWithPenKeys() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val decoded = ctx.assets.open("v1_page.ink").use { PageLayerCodec.decode(it) }
        assertThat(decoded).hasSize(3)
        val keys = decoded.map { (it as InkElement).stroke.brush.family.developerComment }
        assertThat(keys).containsExactly(
            "samesame/Fountain/pressure", "samesame/Calligraphy/fixed", "samesame/Highlighter/pressure",
        ).inOrder()
        assertThat(decoded.map { it.id.value }.toSet()).hasSize(3)
        assertThat((decoded[0] as InkElement).stroke.inputs.size).isEqualTo(2)
    }
}

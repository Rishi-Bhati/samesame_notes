package org.samesame.notes.core.sdocx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.sdocx.io.LeReader
import org.samesame.notes.core.sdocx.record.ObjectEntry
import kotlin.math.abs

/**
 * Stroke payload checks against the four device fixtures f01-f04 (objects spec §4.3). The fixtures are
 * in `src/test/resources/sdocx`; `sdocx_inspect.py --verify-strokes` is the independent reference and
 * decodes/re-encodes the same five strokes.
 */
class SdocxStrokeCodecTest {

    private val fixtures = listOf("f01-dot", "f02-line-h", "f03-line-v", "f04-two-strokes")

    private fun archive(name: String): SdocxArchive {
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream("sdocx/$name.sdocx")) {
            "missing fixture $name.sdocx"
        }.use { it.readBytes() }
        return SdocxReader().read(bytes)
    }

    private fun strokeEntries(name: String): List<ObjectEntry> =
        archive(name).pages.flatMap { page -> page.layers.flatMap { it.objects } }
            .filter { it.objectType == SdocxStrokeCodec.OBJECT_TYPE_STROKE }

    private fun payloadOf(entry: ObjectEntry): ByteArray {
        val base = LeReader(entry.record).i32()
        return entry.record.copyOfRange(base, entry.record.size)
    }

    @Test
    fun `every fixture stroke re-encodes to identical bytes`() {
        var strokes = 0
        for (fixture in fixtures) {
            for (entry in strokeEntries(fixture)) {
                val payload = payloadOf(entry)
                val stroke = checkNotNull(SdocxStrokeCodec.decode(entry)) { "undecodable stroke in $fixture" }
                assertThat(stroke.isFromFile).isTrue()
                assertThat(SdocxStrokeCodec.encodePayload(stroke)).isEqualTo(payload)
                strokes++
            }
        }
        assertThat(strokes).isEqualTo(5)
    }

    @Test
    fun `decoded points match the base rect`() {
        for (fixture in fixtures) {
            for (entry in strokeEntries(fixture)) {
                val stroke = checkNotNull(SdocxStrokeCodec.decode(entry))
                val rect = checkNotNull(SdocxStrokeCodec.rectOf(entry.record))
                val bounds = SdocxStrokeCodec.boundsOf(stroke.points)
                assertThat(abs(rect.left - bounds.left)).isLessThan(1e-6)
                assertThat(abs(rect.top - bounds.top)).isLessThan(1e-6)
                assertThat(abs(rect.right - bounds.right)).isLessThan(1e-6)
                assertThat(abs(rect.bottom - bounds.bottom)).isLessThan(1e-6)
                assertThat(stroke.points.size).isEqualTo(checkNotNull(stroke.raw).aux.size + 1)
            }
        }
    }

    @Test
    fun `fixture f04 first stroke decodes to the worked example`() {
        val entry = strokeEntries("f04-two-strokes").first()
        val stroke = checkNotNull(SdocxStrokeCodec.decode(entry))
        assertThat(stroke.points).containsExactly(
            SdocxStrokePoint(200.0, 1052.0),
            SdocxStrokePoint(300.0, 1052.0),
            SdocxStrokePoint(400.0, 1052.0),
            SdocxStrokePoint(400.0, 1052.0),
        ).inOrder()
        assertThat(stroke.colour).isEqualTo(0xFF335FFFL)
        assertThat(stroke.penSize).isWithin(1e-4f).of(7.65f)
        assertThat(stroke.toolType).isEqualTo(SdocxStrokeCodec.TOOL_SPEN)
        val raw = checkNotNull(stroke.raw)
        // Auxiliary area: zero except the last 0x9000 word; 3n zero words; trailer 0,0,1.
        assertThat(raw.aux.toList()).containsExactly(0, 0, 0x9000).inOrder()
        assertThat(raw.mid.toList()).containsExactlyElementsIn(List(12) { 0 }).inOrder()
        assertThat(raw.trailerFirst).isEqualTo(0)
        assertThat(raw.trailerSecond).isEqualTo(0)
        assertThat(raw.trailerThird).isEqualTo(1)
        assertThat(raw.style.penSize2).isWithin(1e-4f).of(7.65f)
    }

    @Test
    fun `new stroke matches the f04 payload from the writer checklist`() {
        val expected = payloadOf(strokeEntries("f04-two-strokes").first())
        val stroke = SdocxStroke(
            points = listOf(
                SdocxStrokePoint(200.0, 1052.0),
                SdocxStrokePoint(300.0, 1052.0),
                SdocxStrokePoint(400.0, 1052.0),
                SdocxStrokePoint(400.0, 1052.0),
            ),
            colour = 0xFF335FFFL,
            penSize = Float.fromBits(0x40F4CCCC),
        )
        assertThat(SdocxStrokeCodec.encodePayload(stroke)).isEqualTo(expected)
    }

    @Test
    fun `moving a stroke rewrites only the first point and the rect`() {
        val entry = strokeEntries("f04-two-strokes").first()
        val stroke = checkNotNull(SdocxStrokeCodec.decode(entry))
        val moved = SdocxStrokeCodec.withPoints(
            stroke,
            stroke.points.map { SdocxStrokePoint(it.x + 10.0, it.y - 20.0) },
        )
        val record = SdocxStrokeCodec.rewrite(entry.record, moved)
        val decoded = checkNotNull(SdocxStrokeCodec.decode(record))
        assertThat(decoded.points).containsExactly(
            SdocxStrokePoint(210.0, 1032.0),
            SdocxStrokePoint(310.0, 1032.0),
            SdocxStrokePoint(410.0, 1032.0),
            SdocxStrokePoint(410.0, 1032.0),
        ).inOrder()
        val rect = checkNotNull(SdocxStrokeCodec.rectOf(record))
        assertThat(rect.left).isWithin(1e-6).of(210.0)
        assertThat(rect.top).isWithin(1e-6).of(1032.0)
        assertThat(rect.right).isWithin(1e-6).of(410.0)
        // Unknown style/aux fields survive the rewrite byte-for-byte.
        val before = checkNotNull(stroke.raw)
        val after = checkNotNull(decoded.raw)
        assertThat(after.aux.toList()).isEqualTo(before.aux.toList())
        assertThat(after.mid.toList()).isEqualTo(before.mid.toList())
        assertThat(after.style).isEqualTo(before.style)
        assertThat(after.penHeader).isEqualTo(before.penHeader)
        assertThat(record.size).isEqualTo(entry.record.size)
    }

    @Test
    fun `recolouring and resizing keep the unverified style words`() {
        val entry = strokeEntries("f02-line-h").first()
        val stroke = checkNotNull(SdocxStrokeCodec.decode(entry))
        val edited = SdocxStrokeCodec.withStyle(stroke, colour = 0xFFAA0000L, penSize = 3f)
        val decoded = checkNotNull(SdocxStrokeCodec.decodePayload(SdocxStrokeCodec.encodePayload(edited)))
        assertThat(decoded.colour).isEqualTo(0xFFAA0000L)
        assertThat(decoded.penSize).isEqualTo(3f)
        val before = checkNotNull(stroke.raw).style
        val after = checkNotNull(decoded.raw).style
        assertThat(after.version).isEqualTo(before.version)
        assertThat(after.zero).isEqualTo(before.zero)
        assertThat(after.penSize2).isEqualTo(before.penSize2)
        assertThat(after.flags).isEqualTo(before.flags)
        assertThat(after.tension).isEqualTo(before.tension)
    }

    @Test
    fun `new record has the documented base framing`() {
        val stroke = SdocxStroke(
            points = listOf(SdocxStrokePoint(10.0, 20.0), SdocxStrokePoint(42.5, 20.0)),
            colour = 0xFF112233L,
            penSize = 4f,
        )
        val record = SdocxStrokeCodec.newRecord(
            stroke,
            uuid = "11111111-1111-1111-1111-111111111111",
            modifiedTimeUs = 1_700_000_000_000_000L,
            ownerPageWidth = 1080,
            ownerPageHeight = 1527,
        )
        val base = LeReader(record).i32()
        assertThat(base).isEqualTo(121)
        assertThat(record.size).isEqualTo(base + SdocxStrokeCodec.PAYLOAD_OVERHEAD + 12 * 2)
        assertThat(SdocxStrokeCodec.decode(record)).isNotNull()
        val rect = checkNotNull(SdocxStrokeCodec.rectOf(record))
        assertThat(rect.left).isEqualTo(10.0)
        assertThat(rect.right).isEqualTo(42.5)
    }

    @Test
    fun `hostile or truncated payloads are refused`() {
        val payload = payloadOf(strokeEntries("f01-dot").first())
        assertThat(SdocxStrokeCodec.decodePayload(payload.copyOf(payload.size - 1))).isNull()
        val wrongLength = payload.copyOf()
        wrongLength[0] = (payload[0] + 1).toByte()
        assertThat(SdocxStrokeCodec.decodePayload(wrongLength)).isNull()
        assertThat(SdocxStrokeCodec.decodePayload(ByteArray(0))).isNull()
    }

    @Test
    fun `delta words are sign magnitude and zero is negative zero`() {
        assertThat(SdocxStrokeCodec.deltaWord(0.0)).isEqualTo(0x8000)
        assertThat(SdocxStrokeCodec.deltaWord(-0.0)).isEqualTo(0x8000)
        assertThat(SdocxStrokeCodec.deltaWord(70.0)).isEqualTo(70 * 32)
        assertThat(SdocxStrokeCodec.deltaWord(-70.0)).isEqualTo(0x8000 or (70 * 32))
        assertThat(SdocxStrokeCodec.deltaValue(0x8000)).isEqualTo(0.0)
        assertThat(SdocxStrokeCodec.deltaValue(70 * 32)).isEqualTo(70.0)
        assertThat(SdocxStrokeCodec.deltaValue(0x8000 or (70 * 32))).isEqualTo(-70.0)
    }
}

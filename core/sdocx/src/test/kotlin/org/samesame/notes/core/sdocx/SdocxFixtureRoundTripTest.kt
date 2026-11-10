package org.samesame.notes.core.sdocx

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.RichTextOps
import org.samesame.notes.core.sdocx.io.LeReader
import org.samesame.notes.core.sdocx.map.SdocxMapper
import org.samesame.notes.core.sdocx.record.ObjectEntry
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Round trips over every file written by the reference app that we have (`src/test/resources/sdocx`,
 * plus the files in `SAMESAME_SDOCX_FIXTURE_DIR` that are too large for the repository):
 * unchanged export, edits, and what comes back on re-import. With `SAMESAME_SDOCX_INSPECTOR` set to
 * `research/sdocx_inspect.py`, every file written here must also pass its `--lint-writer` rules.
 */
class SdocxFixtureRoundTripTest {

    private val fixtures: List<Pair<String, ByteArray>> =
        SdocxTestFixtures.NAMES.map { it to SdocxTestFixtures.bytes(it) } + SdocxTestFixtures.external()

    private fun strokeEntries(archive: SdocxArchive): List<ObjectEntry> {
        val out = ArrayList<ObjectEntry>()
        fun visit(entry: ObjectEntry) {
            if (entry.objectType == SdocxStrokeCodec.OBJECT_TYPE_STROKE) out += entry
            entry.children.forEach(::visit)
        }
        archive.pages.forEach { page -> page.layers.forEach { layer -> layer.objects.forEach(::visit) } }
        return out
    }

    private fun payloadOf(entry: ObjectEntry): ByteArray =
        entry.record.copyOfRange(LeReader(entry.record).i32(), entry.record.size)

    // ---- unchanged ----

    @Test
    fun `every fixture is rewritten with identical entries and an identical trailing end tag`() {
        assertThat(fixtures.size).isAtLeast(14)
        for ((name, bytes) in fixtures) {
            val written = SdocxWriter().writeToBytes(SdocxReader().read(bytes))
            val before = zip(bytes)
            val after = zip(written)
            assertWithMessage(name).that(after.entries.keys.toList()).isEqualTo(before.entries.keys.toList())
            for ((entry, data) in before.entries) assertWithMessage("$name/$entry").that(after.entries.getValue(entry)).isEqualTo(data)
            assertWithMessage("$name trailer").that(after.trailer).isEqualTo(before.trailer)
            assertWithMessage("$name trailer present").that(before.trailer.size).isGreaterThan(22)
            assertWithMessage("$name headers").that(after.headers).isEqualTo(before.headers)
            lint(written, "$name-rezip")
        }
    }

    @Test
    fun `every fixture stroke decodes and encodes back to its bytes`() {
        var strokes = 0
        for ((name, bytes) in fixtures) {
            for (entry in strokeEntries(SdocxReader().read(bytes))) {
                val stroke = SdocxStrokeCodec.decode(entry)
                assertWithMessage("$name ${entry.uuid}").that(stroke).isNotNull()
                assertWithMessage("$name ${entry.uuid}").that(SdocxStrokeCodec.encodePayload(stroke!!)).isEqualTo(payloadOf(entry))
                strokes++
            }
        }
        assertThat(strokes).isAtLeast(23)
    }

    @Test
    fun `unchanged page ink keeps every fixture archive as it is`() {
        for ((name, bytes) in fixtures) {
            val archive = SdocxReader().read(bytes)
            val edits = archive.pages.associate { page ->
                page.pageId to SdocxInk.pageStrokes(archive, page, SdocxInk.surface(archive, page))
            }
            assertWithMessage(name).that(SdocxStrokeMerge.apply(archive, edits, NOW)).isSameInstanceAs(archive)
            val document = SdocxMapper.toNoteDocument(archive, "n")
            assertWithMessage(name).that(SdocxMapper.applyDocumentEdits(archive, document, NOW)).isSameInstanceAs(archive)
        }
    }

    @Test
    fun `positions rounded by the ink store still count as unchanged`() {
        val archive = SdocxTestFixtures.archive("f16-diagonal")
        val page = archive.pages.first { SdocxInk.sources(it).isNotEmpty() }
        val jittered = SdocxInk.surface(archive, page).map { s ->
            SdocxInkSample(s.id, s.penKey, s.argb, s.size, s.toolType, FloatArray(s.xy.size) { i -> s.xy[i] + if (i % 3 == 0) 0.2f else -0.15f })
        }
        val edits = mapOf(page.pageId to SdocxInk.pageStrokes(archive, page, jittered))
        assertThat(SdocxStrokeMerge.apply(archive, edits, NOW)).isSameInstanceAs(archive)
    }

    // ---- import content ----

    @Test
    fun `the five pens and the highlighter map to our pen families with colour and size`() {
        val pens = SdocxTestFixtures.archive("f05-pens")
        val samples = pens.pages.flatMap { SdocxInk.surface(pens, it) }
        assertThat(samples.map { it.penKey }).containsExactly(
            "samesame/Fountain/pressure", "samesame/Calligraphy/pressure", "samesame/Pen/pressure",
            "samesame/Pencil/pressure", "samesame/Brush/pressure",
        ).inOrder()
        assertThat(samples.map { it.argb }).containsExactly(0xFF335FFFL, 0xFF252525L, 0xFF252525L, 0xA0252525L, 0xFF252525L).inOrder()
        assertThat(samples[0].size).isWithin(1e-3f).of(7.65f)
        assertThat(samples[4].size).isWithin(1e-3f).of(16.0785f)
        assertThat(samples[0].xy.size).isEqualTo(22)
        assertThat(samples[0].xy[0]).isEqualTo(200f)
        assertThat(samples[0].xy[20]).isEqualTo(875f)

        val sizes = SdocxTestFixtures.archive("f07-sizes")
        val stored = sizes.pages.flatMap { SdocxInk.surface(sizes, it) }.map { it.size }
        assertThat(stored).hasSize(3)
        listOf(9.432f, 16.0785f, 31.587f).forEachIndexed { i, size -> assertThat(stored[i]).isWithin(1e-3f).of(size) }
        val colours = SdocxTestFixtures.archive("f06-colours")
        assertThat(colours.pages.flatMap { SdocxInk.surface(colours, it) }.map { it.argb })
            .containsExactly(0xFFFF3636L, 0xFFF7EB00L, 0xFF1447FFL).inOrder()

        val marker = SdocxTestFixtures.archive("f08-highlighter")
        val highlight = marker.pages.flatMap { SdocxInk.surface(marker, it) }.single()
        assertThat(highlight.penKey).isEqualTo("samesame/Highlighter/fixed")
        assertThat(highlight.argb).isEqualTo(0x73FFB426L)
        assertThat(highlight.xy.size).isEqualTo(174)

        val pages = SdocxTestFixtures.archive("f12-pages")
        assertThat(pages.pages.map { SdocxInk.surface(pages, it).size }).containsExactly(1, 1, 0).inOrder()
    }

    @Test
    fun `styled text maps paragraph records by paragraph index`() {
        val document = SdocxMapper.toNoteDocument(SdocxTestFixtures.archive("f09-text-styled"), "n")
        assertThat(document.body.text).isEqualTo("bold line\nred line\nbig line\nbullet item")
        assertThat(document.body.paragraphs.map { it.list })
            .containsExactly(ListKind.None, ListKind.None, ListKind.None, ListKind.Bullet).inOrder()
        val styles = document.body.runs.flatMap { run -> List(run.length) { run.style } }
        assertThat(styles[0].color).isEqualTo(0xFF252525L)
        assertThat(styles[12].color).isEqualTo(0xFFFF3636L)
        assertThat(styles[0].fontSize).isEqualTo(17f)
        assertThat(styles[30].fontSize).isEqualTo(20f)
    }

    // ---- new records reproduce real ones ----

    @Test
    fun `a new title and an empty body are byte for byte the records of a real file`() {
        val real = SdocxTestFixtures.archive("f01-dot")
        val title = real.note.title.raw
        val body = real.note.body.raw
        val titleBase = parseObjectBase(title)!!
        val bodyBase = parseObjectBase(body)!!
        assertThat(SdocxTextCodec.textBox(titleBase.uuid, titleBase.modifiedTime, RichText.of("f01-dot"), titled = true)).isEqualTo(title)
        assertThat(SdocxTextCodec.textBox(bodyBase.uuid, bodyBase.modifiedTime, RichText(), titled = false)).isEqualTo(body)
    }

    @Test
    fun `a new inline image record is byte for byte the record of a real file`() {
        val real = SdocxTestFixtures.archive("f10-image")
        val span = real.note.body.text!!.objectSpans.single()
        val info = SdocxImages.info(span.record)!!
        val base = parseObjectBase(span.record)!!
        assertThat(info.imageId).isEqualTo(0)
        val ours = SdocxImages.record(base.uuid, base.modifiedTime, info.rect, info.imageId, real.note.width)
        // The append time of the real object is earlier than its modified time; ours repeats one stamp.
        val appendTime = 105
        assertThat(ours.size).isEqualTo(span.record.size)
        assertThat(ours.copyOfRange(0, appendTime)).isEqualTo(span.record.copyOfRange(0, appendTime))
        assertThat(ours.copyOfRange(appendTime + 8, ours.size)).isEqualTo(span.record.copyOfRange(appendTime + 8, ours.size))
    }

    @Test
    fun `a new stroke of each real pen has the bytes the reference app wrote for it`() {
        val real = SdocxTestFixtures.archive("f05-pens")
        val profiles = listOf(SdocxPens.FOUNTAIN, SdocxPens.CALLIGRAPHY, SdocxPens.PEN, SdocxPens.PENCIL, SdocxPens.BRUSH)
        strokeEntries(real).forEachIndexed { index, entry ->
            val stroke = SdocxStrokeCodec.decode(entry)!!
            val fresh = SdocxStroke(stroke.points, stroke.colour, stroke.penSize)
            val key = stroke.penNameId!!
            assertWithMessage(profiles[index].suffix)
                .that(SdocxStrokeCodec.encodeNew(fresh, profiles[index], key + 1, key)).isEqualTo(payloadOf(entry))
            assertThat(SdocxInk.penNameOf(real, stroke)).isEqualTo(profiles[index].className)
            assertThat(real.note.properties.stringIdTable!!.first { it.id == key + 1 }.text).isEqualTo(profiles[index].advanced)
        }
    }

    // ---- stroke edits ----

    private class Edited(val before: SdocxArchive, val after: SdocxArchive, val reread: SdocxArchive)

    private fun edit(name: String, label: String, change: (List<SdocxInkSample>) -> List<SdocxInkSample>): Edited {
        val archive = SdocxTestFixtures.archive(name)
        val page = archive.pages.first { SdocxInk.sources(it).isNotEmpty() }
        val edits = mapOf(page.pageId to SdocxInk.pageStrokes(archive, page, change(SdocxInk.surface(archive, page))))
        var next = 0
        val merged = SdocxStrokeMerge.apply(archive, edits, NOW, newUuid = { "00000000-0000-4000-8000-%012d".format(next++) })
        assertWithMessage("$name $label changed").that(merged).isNotSameInstanceAs(archive)
        val bytes = SdocxWriter().writeToBytes(merged)
        val reread = SdocxReader().read(bytes)
        assertWithMessage("$name $label stable").that(SdocxWriter().writeToBytes(reread)).isEqualTo(bytes)
        // Everything but the edited page, the note and the files derived from them is untouched.
        val before = zip(SdocxWriter().writeToBytes(archive)).entries
        val after = zip(bytes).entries
        for ((entry, data) in before) {
            if (entry == "${page.pageId}.page" || entry in setOf("note.note", "pageIdInfo.dat", "end_tag.bin")) continue
            assertWithMessage("$name $label $entry").that(after[entry]).isEqualTo(data)
        }
        assertThat(merged.page(page.pageId)!!.modifiedTime).isEqualTo(NOW)
        assertThat(merged.note.fileRevision).isEqualTo(archive.note.fileRevision + 1)
        assertThat(merged.endTag.modifiedTime).isEqualTo(NOW)
        assertThat(merged.page(page.pageId)!!.properties.canvasCacheRaw).isNull()
        lint(bytes, "$name-$label")
        return Edited(archive, merged, reread)
    }

    private fun moved(s: SdocxInkSample, dx: Float, dy: Float) =
        SdocxInkSample(s.id, s.penKey, s.argb, s.size, s.toolType, FloatArray(s.xy.size) { i -> s.xy[i] + if (i % 2 == 0) dx else dy })

    @Test
    fun `moving a stroke rewrites its first point rect and time and nothing else`() {
        for (name in listOf("f04-two-strokes", "f05-pens", "f08-highlighter", "f12-pages", "f14-eraser")) {
            val result = edit(name, "moved") { samples -> listOf(moved(samples[0], 12.5f, -20.25f)) + samples.drop(1) }
            val before = strokeEntries(result.before)
            val after = strokeEntries(result.reread)
            assertThat(after).hasSize(before.size)
            val a = payloadOf(before[0])
            val b = payloadOf(after[0])
            assertThat(b.size).isEqualTo(a.size)
            // Only x0 and y0 (payload +20..+36) differ.
            assertThat(b.copyOfRange(0, 20)).isEqualTo(a.copyOfRange(0, 20))
            assertThat(b.copyOfRange(36, b.size)).isEqualTo(a.copyOfRange(36, a.size))
            val was = SdocxStrokeCodec.decode(before[0])!!
            val now = SdocxStrokeCodec.decode(after[0])!!
            assertThat(now.points[0].x).isWithin(1e-4).of(was.points[0].x + 12.5)
            assertThat(now.points[0].y).isWithin(1e-4).of(was.points[0].y - 20.25)
            assertThat(SdocxStrokeCodec.rectOf(after[0].record)).isEqualTo(SdocxStrokeCodec.boundsOf(now.points))
            assertThat(after[0].uuid).isEqualTo(before[0].uuid)
            assertThat(after[0].modifiedTime).isEqualTo(NOW)
            assertThat(after[0].originalHash).isNotEqualTo(before[0].originalHash)
            for (i in 1 until before.size) {
                assertWithMessage("$name stroke $i").that(after[i].record).isEqualTo(before[i].record)
                assertThat(after[i].originalHash).isEqualTo(before[i].originalHash)
            }
        }
    }

    @Test
    fun `deleting a stroke drops its record and keeps the rest`() {
        val result = edit("f04-two-strokes", "deleted") { it.drop(1) }
        val before = strokeEntries(result.before)
        val after = strokeEntries(result.reread)
        assertThat(after).hasSize(1)
        assertThat(after[0].record).isEqualTo(before[1].record)
        val all = edit("f05-pens", "cleared") { emptyList() }
        assertThat(strokeEntries(all.reread)).isEmpty()
        // The pens stay in the string table: other notes' strokes are not our business, the table is.
        assertThat(all.reread.note.properties.stringIdTable).hasSize(10)
    }

    @Test
    fun `recolouring and resizing change the colour and both size words only`() {
        val result = edit("f04-two-strokes", "restyled") { samples ->
            val s = samples[0]
            listOf(SdocxInkSample(s.id, s.penKey, 0xFFAA0000L, 12f, s.toolType, s.xy)) + samples.drop(1)
        }
        val a = payloadOf(strokeEntries(result.before)[0])
        val b = payloadOf(strokeEntries(result.reread)[0])
        val style = a.size - 28
        assertThat(b.copyOfRange(0, style + 4)).isEqualTo(a.copyOfRange(0, style + 4))
        assertThat(LeReader(b, style + 4).i32()).isEqualTo(0xFFAA0000.toInt())
        assertThat(LeReader(b, style + 8).f32()).isEqualTo(12f)
        assertThat(b.copyOfRange(style + 12, style + 16)).isEqualTo(a.copyOfRange(style + 12, style + 16))
        assertThat(LeReader(b, style + 16).f32()).isEqualTo(12f)
        assertThat(b.copyOfRange(style + 20, b.size)).isEqualTo(a.copyOfRange(style + 20, a.size))
        assertThat(strokeEntries(result.reread)[1].record).isEqualTo(strokeEntries(result.before)[1].record)
    }

    @Test
    fun `reshaping a stroke writes its new points and keeps its other fields`() {
        val result = edit("f16-diagonal", "scaled") { samples ->
            val s = samples[0]
            listOf(SdocxInkSample(s.id, s.penKey, s.argb, s.size, s.toolType, FloatArray(s.xy.size) { i -> 100f + (s.xy[i] - 100f) * 0.5f }))
        }
        val was = SdocxStrokeCodec.decode(strokeEntries(result.before)[0])!!
        val now = SdocxStrokeCodec.decode(strokeEntries(result.reread)[0])!!
        assertThat(now.points).hasSize(was.points.size)
        now.points.forEachIndexed { i, p ->
            assertThat(p.x).isWithin(0.02).of(100 + (was.points[i].x - 100) * 0.5)
            assertThat(p.y).isWithin(0.02).of(100 + (was.points[i].y - 100) * 0.5)
        }
        assertThat(now.raw!!.auxArea).isEqualTo(was.raw!!.auxArea)
        assertThat(now.raw!!.header).isEqualTo(was.raw!!.header)
        assertThat(now.raw!!.style.rest).isEqualTo(was.raw!!.style.rest)
    }

    @Test
    fun `a stroke drawn here is added with its pen in the string table`() {
        val drawn = SdocxInkSample(42L, "samesame/Pencil/pressure", 0xA0112233L, 5f, SdocxStrokeCodec.TOOL_SPEN, floatArrayOf(300f, 1200f, 250f, 1210.5f, 200f, 1190f))
        val result = edit("f04-two-strokes", "added") { it + drawn }
        val before = strokeEntries(result.before)
        val after = strokeEntries(result.reread)
        assertThat(after).hasSize(3)
        assertThat(after[0].record).isEqualTo(before[0].record)
        assertThat(after[1].record).isEqualTo(before[1].record)
        val stroke = SdocxStrokeCodec.decode(after[2])!!
        // The pen-up repeat of the last point is added, as in every stroke of the reference app.
        assertThat(stroke.points.map { it.x to it.y }).containsExactly(300.0 to 1200.0, 250.0 to 1210.5, 200.0 to 1190.0, 200.0 to 1190.0).inOrder()
        assertThat(stroke.colour).isEqualTo(0xA0112233L)
        assertThat(stroke.penSize).isEqualTo(5f)
        assertThat(SdocxInk.penNameOf(result.reread, stroke)).isEqualTo(SdocxPens.PENCIL.className)
        val table = result.reread.note.properties.stringIdTable!!
        assertThat(table.map { it.id }).containsExactly(3, 2, 1, 0).inOrder()
        assertThat(stroke.raw!!.style.advancedId).isEqualTo(stroke.penNameId!! + 1)
        assertThat(table.first { it.id == 3 }.text).isEqualTo("11;")
        // Same base shape as the source strokes: flags, version, optional mask.
        assertThat(after[2].record.copyOfRange(0, 22)).isEqualTo(before[0].record.copyOfRange(0, 22))
        val samples = result.reread.pages.flatMap { SdocxInk.surface(result.reread, it) }
        assertThat(samples.last().penKey).isEqualTo("samesame/Pencil/pressure")
        // A second export without further edits changes nothing.
        val page = result.reread.pages.first { SdocxInk.sources(it).isNotEmpty() }
        val again = mapOf(page.pageId to SdocxInk.pageStrokes(result.reread, page, SdocxInk.surface(result.reread, page)))
        assertThat(SdocxStrokeMerge.apply(result.reread, again, NOW + 1)).isSameInstanceAs(result.reread)
    }

    @Test
    fun `changing the pen of an imported stroke rewrites it with that pen`() {
        val result = edit("f04-two-strokes", "pen") { samples ->
            val s = samples[0]
            listOf(SdocxInkSample(s.id, "samesame/Highlighter/fixed", 0x73FFB426L, s.size, s.toolType, s.xy)) + samples.drop(1)
        }
        val stroke = SdocxStrokeCodec.decode(strokeEntries(result.reread)[0])!!
        assertThat(SdocxInk.penNameOf(result.reread, stroke)).isEqualTo(SdocxPens.HIGHLIGHTER.className)
        assertThat(strokeEntries(result.reread)[0].uuid).isEqualTo(strokeEntries(result.before)[0].uuid)
        assertThat(payloadOf(strokeEntries(result.reread)[0]).size).isEqualTo(66 + 12 * stroke.points.size)
    }

    @Test
    fun `a stroke record the codec cannot decode is never shown changed or dropped`() {
        val archive = SdocxTestFixtures.archive("f04-two-strokes")
        val page = archive.pages.first { SdocxInk.sources(it).isNotEmpty() }
        val layer = page.layers[0]
        val broken = layer.objects[0].let { e ->
            val record = e.record.copyOf()
            record[LeReader(record).i32() + 6] = 0x7F // style offset no longer matches the point count
            ObjectEntry(e.objectType, e.childCount, record, e.originalHash, e.uuid, e.modifiedTime, e.children)
        }
        val damagedPage = org.samesame.notes.core.sdocx.record.PageRecord(
            page.orientation, page.width, page.height, page.offsetX, page.offsetY, page.pageId, page.modifiedTime,
            page.formatVersion, page.minFormatVersion, page.pageFlags, page.propMask, page.properties,
            listOf(org.samesame.notes.core.sdocx.record.LayerRecord(layer.flags, layer.optMask, layer.layerNumber, layer.optionalRaw, listOf(broken, layer.objects[1]), layer.originalHash, layer.layerId, layer.modifiedTime)),
            page.originalHash, page.currentLayerIndex,
        )
        val damaged = SdocxReader().read(SdocxWriter().writeToBytes(archive.rebuilt(pages = listOf(damagedPage) + archive.pages.drop(1))))
        val damagedFirst = damaged.pages[0]
        assertThat(SdocxInk.surface(damaged, damagedFirst)).hasSize(1)
        // The editor deletes everything it was shown: only the decodable stroke goes.
        val merged = SdocxStrokeMerge.apply(damaged, mapOf(damagedFirst.pageId to emptyList()), NOW)
        val left = strokeEntries(SdocxReader().read(SdocxWriter().writeToBytes(merged)))
        assertThat(left).hasSize(1)
        assertThat(left[0].record).isEqualTo(broken.record)
    }

    // ---- text and pictures ----

    @Test
    fun `a text edit keeps the body object and every page of every fixture`() {
        for ((name, bytes) in fixtures) {
            val archive = SdocxReader().read(bytes)
            val document = SdocxMapper.toNoteDocument(archive, "n")
            val body = RichTextOps.insert(document.body, document.body.text.length, " more")
            val edited = SdocxMapper.applyDocumentEdits(archive, document.copy(body = body, title = document.title + "!"), NOW)
            val written = SdocxWriter().writeToBytes(edited)
            val reread = SdocxReader().read(written)
            val before = zip(bytes).entries
            val after = zip(written).entries
            for ((entry, data) in before) {
                if (entry in setOf("note.note", "pageIdInfo.dat", "end_tag.bin")) continue
                assertWithMessage("$name $entry").that(after[entry]).isEqualTo(data)
            }
            assertWithMessage(name).that(parseObjectBase(reread.note.body.raw)!!.uuid).isEqualTo(parseObjectBase(archive.note.body.raw)!!.uuid)
            assertWithMessage(name).that(parseObjectBase(reread.note.body.raw)!!.modifiedTime).isEqualTo(NOW)
            assertWithMessage(name).that(reread.note.properties.raw).isEqualTo(archive.note.properties.raw)
            val back = SdocxMapper.toNoteDocument(reread, "n")
            assertWithMessage(name).that(back.body.text).isEqualTo(body.text)
            assertWithMessage(name).that(back.title).isEqualTo(document.title + "!")
            assertWithMessage(name).that(back.body.paragraphs).isEqualTo(RichTextOps.normalise(body).paragraphs)
            assertWithMessage(name).that(back.inlineObjects).isEqualTo(document.inlineObjects)
            assertWithMessage(name).that(reread.note.body.text!!.objectSpans.map { it.record.toList() })
                .isEqualTo(archive.note.body.text!!.objectSpans.map { it.record.toList() })
            lint(written, "$name-text")
        }
    }

    @Test
    fun `an inline picture is shown kept deleted and added`() {
        val archive = SdocxTestFixtures.archive("f10-image")
        val document = SdocxMapper.toNoteDocument(archive, "n")
        val picture = document.inlineObjects.single() as ImageObject
        assertThat(picture.media).isEqualTo("media/0@files_261110_215744_536.png")
        assertThat(picture.widthPu).isWithin(0.01f).of(667.385f)
        assertThat(picture.heightPu).isWithin(0.01f).of(1446f)
        assertThat(document.body.text).isEqualTo(OBJECT_CHAR.toString())
        assertThat(document.body.runs.single().style.objectId).isEqualTo(picture.id)

        // Typing next to the picture keeps its record.
        val typed = document.copy(body = RichTextOps.insert(document.body, 1, "\ncaption"))
        val kept = SdocxReader().read(SdocxWriter().writeToBytes(SdocxMapper.applyDocumentEdits(archive, typed, NOW)))
        assertThat(kept.note.body.text!!.objectSpans.single().record).isEqualTo(archive.note.body.text!!.objectSpans.single().record)
        assertThat(kept.note.body.text!!.objectSpans.single().charPos).isEqualTo(0)

        // Deleting it removes the object span; the placeholder is gone with it.
        val removed = SdocxMapper.applyDocumentEdits(archive, document.copy(body = RichText.of("text"), inlineObjects = emptyList()), NOW)
        val removedBytes = SdocxWriter().writeToBytes(removed)
        assertThat(SdocxReader().read(removedBytes).note.body.text!!.objectSpans).isEmpty()
        lint(removedBytes, "f10-picture-removed")

        // A second picture added here becomes a second inline image with its own media file.
        val added = document.copy(
            body = RichTextOps.insertObject(RichTextOps.insert(document.body, 1, "\n"), 2, "mine"),
            inlineObjects = document.inlineObjects + ImageObject("mine", "media/x.png", 300f, 200f),
        )
        val png = byteArrayOf(1, 2, 3, 4, 5)
        val withPicture = SdocxMapper.applyDocumentEdits(
            archive, added, NOW, newImages = mapOf("mine" to SdocxImageSource(png, "png", 300.0, 200.0)),
        )
        val bytes = SdocxWriter().writeToBytes(withPicture)
        val reread = SdocxReader().read(bytes)
        assertThat(reread.note.body.text!!.objectSpans.map { it.charPos }).containsExactly(0, 2).inOrder()
        assertThat(reread.media.entries.map { it.bindNumber }).containsExactly(0, 1).inOrder()
        assertThat(reread.media.entries[1].fileName).startsWith("1@")
        assertThat(reread.mediaFiles["media/" + reread.media.entries[1].fileName]).isEqualTo(png)
        val back = SdocxMapper.toNoteDocument(reread, "n")
        assertThat(back.inlineObjects).hasSize(2)
        assertThat((back.inlineObjects[1] as ImageObject).widthPu).isWithin(0.01f).of(300f)
        lint(bytes, "f10-picture-added")
        // A placeholder nobody has a picture for is not written.
        val orphan = document.copy(body = RichTextOps.insertObject(document.body, 1, "nobody"))
        val cleaned = SdocxReader().read(SdocxWriter().writeToBytes(SdocxMapper.applyDocumentEdits(archive, orphan, NOW)))
        assertThat(cleaned.note.body.text!!.text).isEqualTo(OBJECT_CHAR.toString())
    }

    // ---- notes created here ----

    @Test
    fun `a native note with every pen a picture and two pages reads back and looks like a real file`() {
        val pageA = "3f2a1b0c-9d8e-4f7a-b6c5-d4e3f2a1b0c9"
        val pageB = "7a6b5c4d-3e2f-4a1b-8c9d-0e1f2a3b4c5d"
        val keys = listOf(
            "samesame/Fountain/pressure", "samesame/Calligraphy/pressure", "samesame/Pen/fixed",
            "samesame/Pencil/pressure", "samesame/Brush/pressure", "samesame/Highlighter/fixed", "samesame/Highlighter/round",
        )
        val ink = keys.mapIndexed { i, key ->
            SdocxInkSample(i + 1L, key, 0xFF000000L or (i * 0x203040L), 4f + i, SdocxStrokeCodec.TOOL_SPEN, floatArrayOf(100f, 100f + 50 * i, 300.25f, 90f + 50 * i, 250f, 140f + 50 * i))
        }
        val far = SdocxInkSample(99L, "samesame/Pen/pressure", 0xFF123456L, 3f, SdocxStrokeCodec.TOOL_SPEN, floatArrayOf(5f, 5f, 1075f, 1500f))
        val body = RichTextOps.insertObject(RichText.of("Milk\nEggs\n"), 10, "pic")
        val document = NoteDocument(
            id = "0d4e2c1a-5b6f-4a7e-9c3d-2f1e0a9b8c7d",
            title = "native",
            body = body,
            inlineObjects = listOf(ImageObject("pic", "media/p.png", 400f, 300f)),
            pages = listOf(PageSpec(pageA), PageSpec(pageB)),
        )
        var next = 0
        val archive = SdocxNativeInk.export(
            document, mapOf(pageA to ink, pageB to listOf(far)), NOW,
            inlineImages = mapOf("pic" to SdocxImageSource(byteArrayOf(9, 8, 7), "png", 400.0, 300.0)),
            uuid = { "00000000-0000-4000-8000-%012d".format(next++) },
        )
        val bytes = SdocxWriter().writeToBytes(archive)
        lint(bytes, "native-full")
        val reread = SdocxReader().read(bytes)
        assertThat(SdocxWriter().writeToBytes(reread)).isEqualTo(bytes)

        // Container and header fields as in real files.
        val real = zip(SdocxTestFixtures.bytes("f10-image"))
        val ours = zip(bytes)
        assertThat(ours.headers.map { it.substringAfter(' ') }.toSet()).isEqualTo(real.headers.map { it.substringAfter(' ') }.toSet())
        assertThat(ours.entries.keys.first()).isEqualTo("pageIdInfo.dat")
        assertThat(ours.entries.keys.toList().takeLast(2)).containsExactly("note.note", "end_tag.bin").inOrder()
        assertThat(reread.endTag.formatVersion).isEqualTo(4000)
        assertThat(reread.note.width).isEqualTo(1080)
        assertThat(reread.note.height).isEqualTo(2 * 1527 + 24)
        assertThat(reread.note.propMask).isEqualTo(0xC8E80)
        assertThat(reread.media.extended).isTrue()
        assertThat(reread.pages.map { it.layers.size }).containsExactly(1, 1)
        assertThat(reread.pages.map { it.propMask }).containsExactly(0x71, 0x71)
        assertThat(ours.trailer.size).isEqualTo(ours.entries.getValue("end_tag.bin").size)

        // Content.
        val back = SdocxMapper.toNoteDocument(reread, "n")
        assertThat(back.title).isEqualTo("native")
        assertThat(back.body.text).isEqualTo(body.text)
        assertThat((back.inlineObjects.single() as ImageObject).widthPu).isWithin(0.01f).of(400f)
        val first = SdocxInk.surface(reread, reread.pages[0])
        assertThat(first.map { it.penKey }).containsExactly(
            "samesame/Fountain/pressure", "samesame/Calligraphy/pressure", "samesame/Pen/pressure",
            "samesame/Pencil/pressure", "samesame/Brush/pressure", "samesame/Highlighter/fixed", "samesame/Highlighter/fixed",
        ).inOrder()
        first.forEachIndexed { i, s ->
            assertThat(s.argb).isEqualTo(ink[i].argb)
            assertThat(s.size).isEqualTo(ink[i].size)
            assertThat(s.xy.toList().take(6)).isEqualTo(ink[i].xy.toList())
        }
        // A step longer than one delta word holds is cut into shorter ones on the same line.
        val long = SdocxInk.surface(reread, reread.pages[1]).single()
        assertThat(long.xy.size).isGreaterThan(6)
        assertThat(long.xy[0]).isEqualTo(5f)
        assertThat(long.xy[long.xy.size - 2]).isWithin(0.02f).of(1075f)
        assertThat(long.xy[long.xy.size - 1]).isWithin(0.02f).of(1500f)
        assertThat(reread.note.properties.pen!!.penName).isEqualTo(SdocxPens.PEN.className)
        for (entry in strokeEntries(reread)) {
            val stroke = SdocxStrokeCodec.decode(entry)!!
            assertThat(stroke.raw!!.style.advancedId).isEqualTo(stroke.penNameId!! + 1)
            assertThat(SdocxStrokeCodec.rectOf(entry.record)).isEqualTo(SdocxStrokeCodec.boundsOf(stroke.points))
        }
    }

    @Test
    fun `a stroke with more points than one record holds is written as consecutive records`() {
        val n = SdocxStrokeCodec.MAX_POINTS + 10
        val points = List(n) { SdocxStrokePoint(10.0 + it * 0.5, 20.0) }
        val pieces = SdocxStrokeCodec.encodable(points)
        assertThat(pieces).hasSize(2)
        assertThat(pieces.all { it.size <= SdocxStrokeCodec.MAX_POINTS }).isTrue()
        assertThat(pieces[1].first()).isEqualTo(pieces[0][pieces[0].size - 2])
        assertThat(pieces[1].last()).isEqualTo(points.last())
    }

    // ---- locked notes ----

    @Test
    fun `a locked source note stays locked through every export path`() {
        val source = SdocxTestFixtures.archive("f04-two-strokes")
        val tag = source.endTag
        val lockedTag = org.samesame.notes.core.sdocx.record.EndTagRecord(
            tag.formatVersion, tag.noteId, tag.modifiedTime, tag.propertyFlags, tag.coverImage, tag.noteWidth, tag.noteHeight,
            tag.appName, tag.appMajorVersion, tag.appMinorVersion, tag.appPatchName, tag.minFormatVersion, tag.createdTime,
            tag.lastViewedPageIndex, tag.pageMode, SdocxFormat.DocumentType.LOCKED_SDOCX, "owner-guid", tag.reserved1, tag.reserved2,
            tag.displayCreatedTime, tag.displayModifiedTime, tag.lastRecognizedDataModifiedTime, tag.fixedFont,
            tag.fixedTextDirection, tag.fixedBackgroundTheme, tag.hasTail, tag.hasFixedFontGroup, tag.tailExtra,
        )
        val locked = SdocxReader().read(SdocxWriter().writeToBytes(source.withEndTag(lockedTag)))
        assertThat(locked.isLocked).isTrue()
        val page = locked.pages.first { SdocxInk.sources(it).isNotEmpty() }
        val document = SdocxMapper.toNoteDocument(locked, "n")
        val inked = SdocxStrokeMerge.apply(locked, mapOf(page.pageId to emptyList()), NOW)
        val texted = SdocxMapper.applyDocumentEdits(inked, document.copy(title = "changed"), NOW, lastViewedPageIndex = 1)
        val viewed = SdocxMapper.applyDocumentEdits(locked, document, NOW, lastViewedPageIndex = 1)
        for (archive in listOf(inked, texted, viewed)) {
            val bytes = SdocxWriter().writeToBytes(archive)
            val reread = SdocxReader().read(bytes)
            assertThat(reread.endTag.documentType).isEqualTo(SdocxFormat.DocumentType.LOCKED_SDOCX)
            assertThat(reread.endTag.ownerId).isEqualTo("owner-guid")
            assertThat(reread.trailer!!.documentType).isEqualTo(SdocxFormat.DocumentType.LOCKED_SDOCX)
            assertThat(reread.trailer!!.ownerId).isEqualTo("owner-guid")
        }
    }

    // ---- helpers ----

    private class Zip(val entries: LinkedHashMap<String, ByteArray>, val headers: List<String>, val trailer: ByteArray)

    /** Entries in file order, the central directory's method/flag/version words per entry, and the bytes after the ZIP. */
    private fun zip(bytes: ByteArray): Zip {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        var eocd = bytes.size - 22
        while (!(bytes[eocd] == 0x50.toByte() && bytes[eocd + 1] == 0x4B.toByte() && bytes[eocd + 2] == 5.toByte() && bytes[eocd + 3] == 6.toByte())) eocd--
        val headers = ArrayList<String>()
        var at = LeReader(bytes, eocd + 16).i32()
        repeat(LeReader(bytes, eocd + 10).u16()) {
            val r = LeReader(bytes, at + 4)
            val madeBy = r.u16(); val needed = r.u16(); val flags = r.u16(); val method = r.u16()
            val nameLen = LeReader(bytes, at + 28).u16()
            val extraLen = LeReader(bytes, at + 30).u16()
            val commentLen = LeReader(bytes, at + 32).u16()
            val name = String(bytes, at + 46, nameLen, Charsets.UTF_8)
            val local = LeReader(bytes, at + 42).i32()
            val localFlags = LeReader(bytes, local + 6).u16()
            val localSize = LeReader(bytes, local + 18).i32()
            val kind = if (name.startsWith("media/") && name != "media/mediaInfo.dat") "media" else name.substringAfterLast('.')
            headers += "$name $kind made=$madeBy need=$needed flags=$flags local=$localFlags method=$method extra=$extraLen comment=$commentLen sized=${localSize > 0}"
            at += 46 + nameLen + extraLen + commentLen
        }
        return Zip(entries, headers, bytes.copyOfRange(eocd + 22 + LeReader(bytes, eocd + 20).u16(), bytes.size))
    }

    private fun lint(bytes: ByteArray, name: String) {
        val inspector = System.getenv("SAMESAME_SDOCX_INSPECTOR")?.takeIf { File(it).isFile } ?: return
        val dir = Files.createTempDirectory("sdocx-lint").toFile()
        try {
            val file = File(dir, "$name.sdocx").apply { writeBytes(bytes) }
            for (mode in listOf(listOf("--lint-writer"), emptyList())) {
                val process = ProcessBuilder(listOf("python3", "-I", inspector) + mode + file.absolutePath).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                val exit = process.waitFor()
                assertWithMessage("$name ${mode.joinToString()}\n$output").that(Regex("\\bFAIL\\b").containsMatchIn(output)).isFalse()
                assertWithMessage("$name ${mode.joinToString()}\n$output").that(exit).isEqualTo(0)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000_000L
    }
}

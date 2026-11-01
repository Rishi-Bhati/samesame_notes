package org.samesame.notes.core.ink.tools

import android.util.Log
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.ink.PenBrushes
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.ink.page.PageLayerCodec
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.box
import org.samesame.notes.core.model.PenSettings
import org.samesame.notes.core.model.PenType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class InkToolsTest {
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 2, type: PenType = PenType.Pen, size: Float = 4f): Stroke {
        val b = MutableStrokeInputBatch()
        for (i in 0 until steps) {
            val t = i / (steps - 1f)
            b.add(InputToolType.STYLUS, x = x0 + (x1 - x0) * t, y = y0 + (y1 - y0) * t, elapsedTimeMillis = i * 10L, pressure = 0.5f)
        }
        return Stroke(PenBrushes.brushFor(PenSettings(type, 0xFF000000, size, false)), b)
    }

    private var nextId = 1000L
    private fun el(id: Long, s: Stroke) = InkElement(ElementId(id), s)
    private fun ids() = { ElementId(nextId++) }

    @Test
    fun areaEraserOnXGivesFourPieces() {
        val x = listOf(el(1, line(0f, 0f, 100f, 100f)), el(2, line(100f, 0f, 0f, 100f)))
        val r = AreaEraser.eraseAlong(x, floatArrayOf(50f, 50f), 8f, newId = ids())
        assertThat(r.elements).hasSize(4)
        assertThat(r.removed.map { it.id.value }).containsExactly(1L, 2L)
        assertThat(r.added).hasSize(4)
        // pieces are clean cuts around the centre, not shortened to input points
        for (p in r.elements) {
            val s = (p as InkElement).stroke.inputs
            val a = s.get(0); val b = s.get(s.size - 1)
            val d = minOf(Math.hypot((a.x - 50f).toDouble(), (a.y - 50f).toDouble()), Math.hypot((b.x - 50f).toDouble(), (b.y - 50f).toDouble()))
            assertThat(d).isWithin(0.5).of(10.0) // radius 8 + half width 2
        }
    }

    @Test
    fun areaEraserDragSplitsAndLeavesUntouchedStrokes() {
        val elements = listOf(el(1, line(0f, 0f, 200f, 0f, steps = 21)), el(2, line(0f, 500f, 200f, 500f)))
        val r = AreaEraser.eraseAlong(elements, floatArrayOf(40f, -20f, 40f, 20f, 120f, 20f, 120f, -20f), 5f, newId = ids())
        assertThat(r.elements.map { it.id.value }).contains(2L)
        assertThat(r.elements).hasSize(4) // 1 -> three pieces (left, middle, right)... plus stroke 2
        assertThat(r.removed.map { it.id.value }).containsExactly(1L)
    }

    @Test
    fun strokeEraserRemovesOnlyTouched() {
        val elements = (0 until 5).map { el(it + 1L, line(0f, it * 100f, 300f, it * 100f)) }
        val r = StrokeEraser.eraseAlong(elements, floatArrayOf(150f, 190f, 150f, 310f), 6f)
        assertThat(r.removed.map { it.id.value }).containsExactly(3L, 4L) // y=200 and y=300
        assertThat(r.elements.map { it.id.value }).containsExactly(1L, 2L, 5L).inOrder()
    }

    @Test
    fun highlighterOnlyFilter() {
        val elements = listOf(
            el(1, line(0f, 0f, 100f, 0f, type = PenType.Highlighter, size = 20f)),
            el(2, line(0f, 0f, 100f, 0f)),
        )
        val r = StrokeEraser.eraseAlong(elements, floatArrayOf(50f, 0f), 5f, highlighterOnly = true)
        assertThat(r.removed.map { it.id.value }).containsExactly(1L)
        val a = AreaEraser.eraseAlong(elements, floatArrayOf(50f, 0f), 5f, highlighterOnly = true, newId = ids())
        assertThat(a.removed.map { it.id.value }).containsExactly(1L)
        assertThat(a.elements.map { it.id.value }).contains(2L)
    }

    @Test
    fun lassoHalfCoveringNeedsIncludePartial() {
        val elements = listOf(el(1, line(0f, 50f, 100f, 50f, steps = 11)), el(2, line(0f, 300f, 100f, 300f)))
        val lasso = floatArrayOf(-10f, 0f, 50f, 0f, 50f, 100f, -10f, 100f)
        assertThat(SelectionHitTester.lasso(elements, lasso, includePartial = false)).isEmpty()
        assertThat(SelectionHitTester.lasso(elements, lasso, includePartial = true).map { it.id.value }).containsExactly(1L)
        val full = floatArrayOf(-10f, 0f, 110f, 0f, 110f, 100f, -10f, 100f)
        assertThat(SelectionHitTester.lasso(elements, full, includePartial = false).map { it.id.value }).containsExactly(1L)
    }

    @Test
    fun rectangleSelectionAndObjects() {
        val obj = ObjectElement(ElementId(9), "{}", box(200f, 200f, 300f, 300f))
        val elements = listOf(el(1, line(0f, 50f, 100f, 50f)), obj)
        assertThat(SelectionHitTester.rectangle(elements, -5f, 0f, 50f, 100f, false)).isEmpty()
        assertThat(SelectionHitTester.rectangle(elements, -5f, 0f, 50f, 100f, true)).hasSize(1)
        assertThat(SelectionHitTester.rectangle(elements, -5f, 0f, 110f, 100f, false)).hasSize(1)
        assertThat(SelectionHitTester.rectangle(elements, 150f, 150f, 250f, 250f, false)).isEmpty()
        assertThat(SelectionHitTester.rectangle(elements, 150f, 150f, 250f, 250f, true)).containsExactly(obj)
        assertThat(SelectionHitTester.rectangle(elements, 190f, 190f, 310f, 310f, false)).containsExactly(obj)
        val l = SelectionHitTester.lasso(elements, floatArrayOf(190f, 190f, 310f, 190f, 310f, 310f, 190f, 310f), false)
        assertThat(l).containsExactly(obj)
    }

    @Test
    fun transformsRoundTripThroughCodec() {
        val s = line(10f, 20f, 110f, 70f, steps = 6, size = 6f)
        val m = StrokeTransforms.compose(StrokeTransforms.scaleAbout(2f, 2f, 0f, 0f), StrokeTransforms.translate(30f, -5f))
        val moved = StrokeTransforms.apply(s, m)
        val p0 = moved.inputs.get(0); val p5 = moved.inputs.get(5)
        assertThat(p0.x).isWithin(1e-3f).of(50f); assertThat(p0.y).isWithin(1e-3f).of(35f)
        assertThat(p5.x).isWithin(1e-3f).of(250f); assertThat(p5.y).isWithin(1e-3f).of(135f)
        assertThat(moved.brush.size).isWithin(1e-3f).of(12f)
        assertThat(moved.inputs.get(3).pressure).isEqualTo(s.inputs.get(3).pressure)

        val rot = StrokeTransforms.apply(s, StrokeTransforms.rotateAbout(90f, 0f, 0f)) // (x,y)->(-y,x)
        assertThat(rot.inputs.get(0).x).isWithin(1e-3f).of(-20f); assertThat(rot.inputs.get(0).y).isWithin(1e-3f).of(10f)

        val bytes = ByteArrayOutputStream().also {
            PageLayerCodec.encode(PageContent("p", listOf(InkElement(ElementId(5), moved)), 1), it)
        }.toByteArray()
        val back = (PageLayerCodec.decode(ByteArrayInputStream(bytes)).single() as InkElement).stroke
        assertThat(back.inputs.size).isEqualTo(6)
        assertThat(back.inputs.get(5).x).isWithin(1e-3f).of(250f)
        assertThat(back.brush.size).isWithin(1e-3f).of(12f)

        val styled = StrokeTransforms.restyle(s, colorArgb = 0xFFFF0000.toInt(), sizePu = 9f, familyKey = "samesame/Pencil/fixed")
        assertThat(styled.brush.family.developerComment).isEqualTo("samesame/Pencil/fixed")
        assertThat(styled.brush.colorIntArgb).isEqualTo(0xFFFF0000.toInt())
        assertThat(styled.inputs.size).isEqualTo(6)
        assertThat(styled.inputs.get(2).x).isEqualTo(s.inputs.get(2).x)
    }

    @Test
    fun benchmarkAgainstBudgets() {
        val rnd = Random(7)
        fun page(n: Int): List<PageElement> = (0 until n).map { i ->
            val cx = rnd.nextFloat() * 1000f; val cy = rnd.nextFloat() * 1500f
            val b = MutableStrokeInputBatch()
            for (k in 0 until 60) b.add(
                InputToolType.STYLUS, x = cx + k * 1.5f, y = cy + Math.sin(k / 6.0).toFloat() * 20f,
                elapsedTimeMillis = k * 8L, pressure = 0.5f,
            )
            el(i + 1L, Stroke(PenBrushes.brushFor(PenSettings(PenType.Fountain, 0xFF000000, 4f, true)), b))
        }
        val elements = page(2000)
        val sb = StringBuilder()
        fun time(label: String, reps: Int, block: (Int) -> Unit): Double {
            repeat(30) { block(it) }
            val t0 = System.nanoTime(); repeat(reps) { block(it) }
            val ms = (System.nanoTime() - t0) / 1e6 / reps
            sb.append("$label=${"%.3f".format(ms)}ms; "); return ms
        }
        val buildMs = run { val t0 = System.nanoTime(); SpatialIndex(elements); (System.nanoTime() - t0) / 1e6 }
        sb.append("indexBuild2000=${"%.2f".format(buildMs)}ms; ")
        val se = StrokeEraser(elements, 12f)
        val sMs = time("strokeEraserMove", 300) { i -> val x = (i * 37 % 1000).toFloat(); val y = (i * 91 % 1500).toFloat(); se.erase(x, y, x + 8f, y + 8f) }
        val ae = AreaEraser(elements, 12f, newId = ids())
        time("areaEraserMove", 300) { i -> val x = (i * 37 % 1000).toFloat(); val y = (i * 91 % 1500).toFloat(); ae.erase(x, y, x + 8f, y + 8f) }
        val ae2 = AreaEraser(elements, 12f, newId = ids())
        val aMiss = time("areaEraserMoveMissOnly", 300) { i -> ae2.erase(-500f - i, -500f, -490f - i, -490f) }
        val one = elements[0] as InkElement
        val ox = one.stroke.inputs.get(30)
        time("splitOneStroke60pts", 100) { StrokeSplitter.split(one.stroke, floatArrayOf(ox.x, ox.y), 12f) }
        val idx = SpatialIndex(elements)
        val lasso = floatArrayOf(300f, 300f, 700f, 320f, 720f, 700f, 310f, 690f)
        val lMs = time("lassoFull2000", 10) { SelectionHitTester.lasso(elements, lasso, true, idx) }
        time("lassoNoIndex2000", 10) { SelectionHitTester.lasso(elements, lasso, false) }
        val rMs = time("rect2000", 10) { SelectionHitTester.rectangle(elements, 300f, 300f, 700f, 700f, false, idx) }
        val onePage = page(1000)
        val t0 = System.nanoTime()
        val bytes = ByteArrayOutputStream().also { PageLayerCodec.encode(PageContent("p", onePage, 1), it) }.toByteArray()
        val enc = (System.nanoTime() - t0) / 1e6
        val moveAll = StrokeTransforms.translate(5f, 5f)
        val t1 = System.nanoTime()
        StrokeTransforms.apply(onePage, onePage.map { it.id }.toSet(), moveAll)
        val tr = (System.nanoTime() - t1) / 1e6
        sb.append("encode1000=${"%.1f".format(enc)}ms (${bytes.size / 1024}KB); bakeTransform1000=${"%.1f".format(tr)}ms")
        Log.i("InkToolsBench", sb.toString())
        println("InkToolsBench: $sb")
        assertWithMessage(sb.toString()).that(sMs).isLessThan(2.0)
        assertWithMessage(sb.toString()).that(aMiss).isLessThan(2.0)
        assertWithMessage(sb.toString()).that(lMs).isLessThan(50.0)
        assertWithMessage(sb.toString()).that(rMs).isLessThan(50.0)
    }
}

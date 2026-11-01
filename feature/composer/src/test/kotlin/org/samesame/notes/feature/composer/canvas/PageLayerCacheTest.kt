package org.samesame.notes.feature.composer.canvas

import androidx.compose.animation.core.FloatExponentialDecaySpec
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The graphics-free parts of the page layer cache: keys, slots, retention window, prefetch order, fling decay. */
class PageLayerCacheTest {

    // ---- LayerKey

    @Test fun keyMatchesOnlyWhenEveryFieldMatches() {
        val content = Any()
        val a = LayerKey().set(a = content, b = 3, l1 = 7L, l2 = 9L, width = 1080, height = 1528)
        val b = LayerKey().set(a = content, b = 3, l1 = 7L, l2 = 9L, width = 1080, height = 1528)
        assertThat(a.sameAs(b)).isTrue()
        assertThat(LayerKey().set(a = Any(), b = 3, l1 = 7L, l2 = 9L, width = 1080, height = 1528).sameAs(a)).isFalse()
        assertThat(LayerKey().set(a = content, b = 4, l1 = 7L, l2 = 9L, width = 1080, height = 1528).sameAs(a)).isFalse()
        assertThat(LayerKey().set(a = content, b = 3, l1 = 8L, l2 = 9L, width = 1080, height = 1528).sameAs(a)).isFalse()
        assertThat(LayerKey().set(a = content, b = 3, l1 = 7L, l2 = 9L, l3 = 1L, width = 1080, height = 1528).sameAs(a)).isFalse()
        assertThat(LayerKey().set(a = content, b = 3, l1 = 7L, l2 = 9L, width = 1080, height = 1529).sameAs(a)).isFalse()
        assertThat(LayerKey().set(a = content, b = 3, c = setOf(1L), l1 = 7L, l2 = 9L, width = 1080, height = 1528).sameAs(a)).isFalse()
    }

    @Test fun keyComparesObjectsByEquality() {
        // Equal sets (a live selection rebuilt every frame with the same ids) must not force a re-record.
        val a = LayerKey().set(c = setOf(1L, 2L), width = 10, height = 10)
        val b = LayerKey().set(c = hashSetOf(2L, 1L), width = 10, height = 10)
        assertThat(a.sameAs(b)).isTrue()
    }

    @Test fun copyFromMakesTheSlotKeyMatchAndTheScratchKeyReusable() {
        val scratch = LayerKey().set(a = "x", l1 = 1L, width = 5, height = 6)
        val held = LayerKey()
        assertThat(held.sameAs(scratch)).isFalse() // a fresh slot never matches
        held.copyFrom(scratch)
        assertThat(held.sameAs(scratch)).isTrue()
        scratch.set(a = "x", l1 = 2L, width = 5, height = 6) // reused for the next layer
        assertThat(held.sameAs(scratch)).isFalse()
        assertThat(held.l1).isEqualTo(1L)
    }

    // ---- LayerSlots

    private class FakeLayer(val n: Int) { var released = false }

    private fun slots(maxPages: Int = LayerSlots.MAX_PAGES): Pair<LayerSlots<FakeLayer>, MutableList<FakeLayer>> {
        val made = mutableListOf<FakeLayer>()
        val s = LayerSlots(create = { FakeLayer(made.size).also { made += it } }, release = { it.released = true }, maxPages = maxPages)
        return s to made
    }

    @Test fun aSlotIsCreatedOncePerPageAndKind() {
        val (s, made) = slots()
        val bg = s.slot("p1", LayerSlots.KIND_BACKGROUND)
        assertThat(s.slot("p1", LayerSlots.KIND_BACKGROUND)).isSameInstanceAs(bg)
        assertThat(s.slot("p1", LayerSlots.KIND_TEXT)).isNotSameInstanceAs(bg)
        assertThat(made).hasSize(2)
        assertThat(s.pageCount).isEqualTo(1)
    }

    @Test fun aSlotIsStaleUntilRecordedWithTheSameKey() {
        val (s, _) = slots()
        val slot = s.slot("p1", LayerSlots.KIND_INK)
        val k = LayerKey().set(a = "content-v1", width = 1080, height = 1500)
        assertThat(slot.isStale(k)).isTrue()
        slot.key.copyFrom(k)
        assertThat(slot.isStale(k)).isFalse()
        k.set(a = "content-v2", width = 1080, height = 1500)
        assertThat(slot.isStale(k)).isTrue()
    }

    @Test fun retainOnlyReleasesEveryLayerOfTheDroppedPages() {
        val (s, made) = slots()
        for (p in listOf("p1", "p2", "p3")) for (k in 0 until LayerSlots.KINDS) s.slot(p, k)
        s.retainOnly { it == "p2" }
        assertThat(s.pageCount).isEqualTo(1)
        assertThat(made.count { it.released }).isEqualTo(2 * LayerSlots.KINDS)
        assertThat(made.filter { !it.released }).hasSize(LayerSlots.KINDS)
        // A dropped page comes back with fresh (stale) slots.
        assertThat(s.slot("p1", LayerSlots.KIND_TEXT).isStale(LayerKey().set(width = 1, height = 1))).isTrue()
    }

    @Test fun theHardCapReleasesTheLeastRecentlyUsedPage() {
        val (s, made) = slots(maxPages = 2)
        val first = s.slot("p1", 0).layer
        s.slot("p2", 0)
        s.slot("p1", 0) // p1 used again: p2 is now the eldest
        s.slot("p3", 0)
        assertThat(s.pageCount).isEqualTo(2)
        assertThat(first.released).isFalse()
        assertThat(made[1].released).isTrue()
    }

    @Test fun clearReleasesEverything() {
        val (s, made) = slots()
        s.slot("a", 0); s.slot("b", 3)
        s.clear()
        assertThat(made.all { it.released }).isTrue()
        assertThat(s.pageCount).isEqualTo(0)
    }

    // ---- LayerRetention

    @Test fun windowIsVisiblePlusOnePageClampedToTheDocument() {
        assertThat(LayerRetention.window(3..4, 10)).isEqualTo(2..5)
        assertThat(LayerRetention.window(0..0, 10)).isEqualTo(0..1)
        assertThat(LayerRetention.window(9..9, 10)).isEqualTo(8..9)
        assertThat(LayerRetention.window(0..0, 1)).isEqualTo(0..0)
        assertThat(LayerRetention.window(IntRange.EMPTY, 10).isEmpty()).isTrue()
        assertThat(LayerRetention.window(2..2, 10, margin = 2)).isEqualTo(0..4)
    }

    @Test fun neighbourPrefersThePageInTheScrollDirection() {
        assertThat(LayerRetention.neighbour(3..4, 10, forward = true, which = 0)).isEqualTo(5)
        assertThat(LayerRetention.neighbour(3..4, 10, forward = true, which = 1)).isEqualTo(2)
        assertThat(LayerRetention.neighbour(3..4, 10, forward = false, which = 0)).isEqualTo(2)
        assertThat(LayerRetention.neighbour(3..4, 10, forward = false, which = 1)).isEqualTo(5)
        assertThat(LayerRetention.neighbour(9..9, 10, forward = true, which = 0)).isEqualTo(-1)
        assertThat(LayerRetention.neighbour(0..0, 10, forward = false, which = 0)).isEqualTo(-1)
        assertThat(LayerRetention.neighbour(IntRange.EMPTY, 10, forward = true, which = 0)).isEqualTo(-1)
    }

    // ---- FlingDecay

    @Test fun flingDecayFollowsTheExponentialDecaySpecPerAxis() {
        val vx = 1800f
        val vy = -5200f
        val decay = FlingDecay(vx, vy, 1.6f)
        val spec = FloatExponentialDecaySpec(1.6f)
        for (ms in listOf(0L, 16L, 100L, 400L)) {
            val t = ms * 1_000_000L
            assertThat(decay.valueX(t)).isWithin(1e-3f).of(spec.getValueFromNanos(t, 0f, vx))
            assertThat(decay.valueY(t)).isWithin(1e-3f).of(spec.getValueFromNanos(t, 0f, vy))
        }
        assertThat(decay.valueX(0L)).isEqualTo(0f)
    }

    @Test fun flingDecayEndsWithTheSlowerAxisAndStopsMoving() {
        val decay = FlingDecay(300f, 6000f, 1.6f)
        val spec = FloatExponentialDecaySpec(1.6f)
        val end = spec.getDurationNanos(0f, 6000f)
        assertThat(decay.isFinished(end - 1)).isFalse()
        assertThat(decay.isFinished(end)).isTrue()
        // Past the end the offsets stay where they ended.
        assertThat(decay.valueY(end * 3)).isEqualTo(decay.valueY(end))
        assertThat(decay.valueY(end)).isWithin(1f).of(spec.getTargetValue(0f, 6000f))
    }

    @Test fun flingDecayHonoursTheAnimatorDurationScale() {
        val decay = FlingDecay(0f, 4000f, 1.6f)
        val tenMs = 10_000_000L
        assertThat(decay.playTime(tenMs, 1f)).isEqualTo(tenMs)
        assertThat(decay.playTime(tenMs, 2f)).isEqualTo(tenMs / 2)
        assertThat(decay.isFinished(decay.playTime(tenMs, 0f))).isTrue() // animations off: jump to the end
    }
}

package org.samesame.notes.feature.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreviewRequestsTest {
    private val requests = PreviewRequests<String>()

    @Test fun theFirstCardStartsARenderAndLaterCardsJoinIt() {
        val a = requests.want("n1")
        assertThat(a.launched).isFalse()
        a.launched = true
        val b = requests.want("n1")
        assertThat(b).isSameInstanceAs(a)
        assertThat(b.launched).isTrue()
        assertThat(requests.isInFlight("n1")).isTrue()
    }

    @Test fun aQueuedRenderIsCancelledWhenItsLastCardLeaves() {
        var cancelled = 0
        val r = requests.want("n1").also { it.launched = true; it.cancel = { cancelled++ } }
        val t1 = requests.ticket(r)
        val t2 = requests.ticket(requests.want("n1"))
        t1.release()
        assertThat(cancelled).isEqualTo(0) // another card still waits
        t2.release()
        assertThat(cancelled).isEqualTo(1)
        assertThat(requests.isInFlight("n1")).isFalse()
    }

    @Test fun aStartedRenderRunsToTheEnd() {
        var cancelled = false
        val r = requests.want("n1").also { it.launched = true; it.cancel = { cancelled = true } }
        val t = requests.ticket(r)
        requests.started(r)
        t.release()
        assertThat(cancelled).isFalse()
        assertThat(requests.isInFlight("n1")).isTrue()
        requests.finished(r)
        assertThat(requests.isInFlight("n1")).isFalse()
    }

    @Test fun releasingATicketTwiceCountsOnce() {
        var cancelled = 0
        val r = requests.want("n1").also { it.launched = true; it.cancel = { cancelled++ } }
        val t1 = requests.ticket(r)
        requests.ticket(requests.want("n1"))
        t1.release()
        t1.release()
        assertThat(cancelled).isEqualTo(0)
        assertThat(requests.isInFlight("n1")).isTrue()
    }

    @Test fun aLateFinishOfACancelledRenderLeavesTheNewRequestAlone() {
        val old = requests.want("n1").also { it.launched = true }
        requests.ticket(old).release() // scrolled away: cancelled and forgotten
        val fresh = requests.want("n1") // scrolled back
        assertThat(fresh).isNotSameInstanceAs(old)
        assertThat(fresh.launched).isFalse()
        requests.finished(old) // the cancelled job's finally runs late
        assertThat(requests.isInFlight("n1")).isTrue()
        assertThat(requests.want("n1")).isSameInstanceAs(fresh)
    }

    @Test fun previewWidthNeverExceedsHalfTheScreen() {
        assertThat(previewWidthFor(1080)).isEqualTo(384)
        assertThat(previewWidthFor(1440)).isEqualTo(384)
        assertThat(previewWidthFor(720)).isEqualTo(360)
        assertThat(previewWidthFor(480)).isEqualTo(240)
        assertThat(previewWidthFor(0)).isEqualTo(384) // unknown (JVM tests)
    }
}

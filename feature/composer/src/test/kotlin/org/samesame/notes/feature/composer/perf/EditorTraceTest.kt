package org.samesame.notes.feature.composer.perf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The open/close trace helper runs inside production paths (session open, page loads, close flush) and in JVM unit tests,
 * where `android.os.Trace` is a stub that throws: it must never swallow a business exception or hide a result.
 */
class EditorTraceTest {

    @Test fun sectionReturnsTheBlockResult() {
        assertThat(EditorTrace.section("open:test") { 42 }).isEqualTo(42)
    }

    @Test fun sectionRunsTheBlockAndClosesTheSection() {
        var ran = false
        EditorTrace.section("open:test") { ran = true }
        assertThat(ran).isTrue()
        // Balanced: a second section still works after the first one closed (an unbalanced stack would not throw, but
        // the helper must not leave the "began" flag behind).
        assertThat(EditorTrace.section("open:test2") { "ok" }).isEqualTo("ok")
    }

    @Test fun sectionLetsBusinessExceptionsThrough() {
        val error = IllegalStateException("save failed")
        val thrown = runCatching { EditorTrace.section("close:test") { throw error } }.exceptionOrNull()
        assertThat(thrown).isSameInstanceAs(error)
    }

    @Test fun beginEndAreHarmlessOnTheUnitTestStub() {
        val began = EditorTrace.begin("open:test")
        EditorTrace.end(began)
        // end without begin (or twice) must not throw either: callers guard with the returned flag.
        EditorTrace.end(false)
    }
}

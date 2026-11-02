package org.samesame.notes.feature.composer.perf

import android.os.Trace

/**
 * System trace sections for the editor's open / close path (Perfetto, systrace slices named `open:..` and `close:..`), so
 * a device capture can show which step of opening (document read, text shaping, session, first draw) or closing (save,
 * pack) is inside a long frame. Same pattern as `traced` in core/data; safe in JVM unit tests, where the `android.os.Trace`
 * stub throws.
 */
internal object EditorTrace {

    /** Runs [block] inside a trace section named [name]. */
    inline fun <T> section(name: String, block: () -> T): T {
        val began = begin(name)
        try {
            return block()
        } finally {
            end(began)
        }
    }

    fun begin(name: String): Boolean = try {
        Trace.beginSection(name)
        true
    } catch (e: RuntimeException) {
        false
    }

    fun end(began: Boolean) {
        if (!began) return
        try {
            Trace.endSection()
        } catch (e: RuntimeException) {
            // tracing was torn down meanwhile: nothing to pair
        }
    }
}

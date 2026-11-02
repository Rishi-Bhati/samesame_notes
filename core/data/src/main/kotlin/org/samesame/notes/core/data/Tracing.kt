package org.samesame.notes.core.data

/**
 * Wraps [block] in a system trace section named [name] (visible in Perfetto / systrace as an app slice), so lock,
 * unlock and preview work can be timed on a device. Safe in JVM unit tests, where `android.os.Trace` is a stub.
 */
internal inline fun <T> traced(name: String, block: () -> T): T {
    val began = try {
        android.os.Trace.beginSection(name)
        true
    } catch (e: RuntimeException) {
        false
    }
    try {
        return block()
    } finally {
        if (began) runCatching { android.os.Trace.endSection() }
    }
}

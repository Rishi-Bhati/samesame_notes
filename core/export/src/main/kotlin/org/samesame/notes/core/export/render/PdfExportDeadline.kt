package org.samesame.notes.core.export.render

import org.samesame.notes.core.model.Limits
import java.util.concurrent.TimeoutException

/** Cooperative deadline checked between export stages; native calls finish before the next check. */
internal class PdfExportDeadline(
    private val timeoutMs: Long = Limits.PDF_VECTOR_EXPORT_TIMEOUT_MS,
    private val clock: () -> Long = System::nanoTime,
) {
    private val started = clock()

    fun check(stage: String) {
        if ((clock() - started) / 1_000_000 >= timeoutMs) throw TimeoutException("PDF export timed out during $stage")
    }
}

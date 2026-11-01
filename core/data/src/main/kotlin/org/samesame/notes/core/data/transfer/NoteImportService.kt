package org.samesame.notes.core.data.transfer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.pdf.PdfImportError
import org.samesame.notes.core.data.pdf.PdfImportException
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.data.repository.SdocxImportError
import org.samesame.notes.core.data.repository.SdocxImportException
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.io.InputTooLargeException
import org.samesame.notes.core.model.io.LimitedInputStream
import org.samesame.notes.core.model.NoteId
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A file this app can turn into a note. */
enum class ImportKind { SDOCX, SSNOTE, PDF }

/**
 * Decides what a file handed to the app is, from its display name (`OpenableColumns.DISPLAY_NAME`), MIME type and
 * URI path. Pure, so it is JVM-tested. The extension is authoritative (`.sdoc` is never a `.sdocx`, whatever MIME a
 * provider reports); without one, the reference's note MIME types and `application/pdf` are accepted. Generic types such as
 * `application/octet-stream` or `application/zip` alone are not enough.
 */
object NoteFileDetector {
    sealed interface Verdict {
        data class Supported(val kind: ImportKind) : Verdict
        /** A legacy `.sdoc` note: device-bound, cannot be imported (the existing "legacy" message). */
        data object Legacy : Verdict
        data object Unsupported : Verdict
    }

    val sdocxMimeTypes: Set<String> = setOf("application/sdoc")
    const val PDF_MIME = "application/pdf"

    fun classify(displayName: String?, mimeType: String?, path: String?): Verdict {
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() }
        val pathName = path?.substringAfterLast('/')?.trim()?.takeIf { it.isNotEmpty() }
        when (extensionOf(name) ?: extensionOf(pathName)) {
            "sdoc" -> return Verdict.Legacy
            "sdocx" -> return Verdict.Supported(ImportKind.SDOCX)
            "ssnote" -> return Verdict.Supported(ImportKind.SSNOTE)
            "pdf" -> return Verdict.Supported(ImportKind.PDF)
        }
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return when {
            mime in sdocxMimeTypes -> Verdict.Supported(ImportKind.SDOCX)
            mime == PDF_MIME -> Verdict.Supported(ImportKind.PDF)
            else -> Verdict.Unsupported
        }
    }

    private fun extensionOf(name: String?): String? {
        if (name == null) return null
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.lastIndex) return null
        return name.substring(dot + 1).lowercase()
    }
}

/** One file to import: what [NoteFileDetector] needs, plus a way to read it (may throw if the file is gone). */
data class ImportSource(
    val displayName: String?,
    val mimeType: String?,
    val path: String?,
    val open: () -> InputStream,
) {
    val label: String get() = displayName ?: path?.substringAfterLast('/').orEmpty()
}

/** Why one file was not imported. */
enum class ImportFailure {
    /** Not a note file this app reads. */
    UNSUPPORTED,
    /** A legacy `.sdoc` (device-bound encryption). */
    LEGACY,
    /** Corrupt, truncated, empty or too large. */
    INVALID,
    /** A password-protected PDF. */
    PROTECTED,
    /** The file could not be read. */
    IO,
}

sealed interface ImportResult {
    data class Imported(val id: NoteId) : ImportResult
    data class Failed(val reason: ImportFailure) : ImportResult
}

/** Totals of a bulk import: [imported] notes in order, [failed] files by reason. */
data class ImportSummary(val imported: List<NoteId>, val failures: Map<ImportFailure, Int>) {
    val failed: Int get() = failures.values.sum()
    val total: Int get() = imported.size + failed
}

/**
 * Imports files into the catalog as ordinary notes (the default folder unless one is given), so they persist even
 * when the source file is deleted later. Used for files opened from other apps (VIEW / SEND) and for the bulk
 * "Import notes" in Settings. `.sdocx` keeps its original archive for lossless re-export (see
 * [NoteRepository.importSdocx]); a PDF becomes one paged note titled after the file (see [NoteRepository.importPdf]).
 */
@Singleton
class NoteImportService internal constructor(
    private val notes: NoteRepository,
    private val scratch: File,
) {
    @Inject constructor(notes: NoteRepository, @ApplicationContext context: Context) :
        this(notes, File(context.cacheDir, "import"))

    suspend fun import(source: ImportSource, folderId: FolderId? = null): ImportResult = withContext(Dispatchers.IO) {
        val kind = when (val v = NoteFileDetector.classify(source.displayName, source.mimeType, source.path)) {
            is NoteFileDetector.Verdict.Supported -> v.kind
            NoteFileDetector.Verdict.Legacy -> return@withContext ImportResult.Failed(ImportFailure.LEGACY)
            NoteFileDetector.Verdict.Unsupported -> return@withContext ImportResult.Failed(ImportFailure.UNSUPPORTED)
        }
        val input = try {
            source.open()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext ImportResult.Failed(ImportFailure.IO)
        }
        try {
            // Never trust the size of a file handed over by another app (endless streams, zip bombs).
            val id = LimitedInputStream(input, Limits.MAX_IMPORT_BYTES, "Imported file").use {
                when (kind) {
                    ImportKind.SDOCX -> notes.importSdocx(it, folderId)
                    ImportKind.SSNOTE -> notes.importNote(it, folderId)
                    ImportKind.PDF -> importPdf(it, pdfTitle(source.label), folderId)
                }
            }
            ImportResult.Imported(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Failed(failureOf(kind, e))
        } catch (e: OutOfMemoryError) {
            ImportResult.Failed(ImportFailure.INVALID) // a hostile file must fail its import, not crash the app
        } catch (e: StackOverflowError) {
            ImportResult.Failed(ImportFailure.INVALID)
        }
    }

    /** Imports [sources] one after another; [onProgress] gets (files done, total, name of the file being read). */
    suspend fun importAll(
        sources: List<ImportSource>,
        folderId: FolderId? = null,
        onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): ImportSummary {
        val imported = ArrayList<NoteId>()
        val failures = LinkedHashMap<ImportFailure, Int>()
        sources.forEachIndexed { index, source ->
            onProgress(index, sources.size, source.label)
            when (val r = import(source, folderId)) {
                is ImportResult.Imported -> imported += r.id
                is ImportResult.Failed -> failures.merge(r.reason, 1, Int::plus)
            }
        }
        onProgress(sources.size, sources.size, "")
        return ImportSummary(imported, failures)
    }

    /** `importPdf` renders from a file (PdfRenderer needs a seekable descriptor), so the stream is copied first. */
    private suspend fun importPdf(input: InputStream, title: String, folderId: FolderId?): NoteId {
        scratch.mkdirs()
        val temp = File.createTempFile("import", ".pdf", scratch)
        try {
            temp.outputStream().use { input.copyTo(it) }
            return notes.importPdf(listOf(temp), title, folderId)
        } finally {
            temp.delete()
        }
    }

    internal companion object {
        /** File name minus a trailing ".pdf" (any case); blank stays blank (the repository picks a default). */
        fun pdfTitle(displayName: String): String =
            displayName.trim().replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "").trim()

        fun failureOf(kind: ImportKind, e: Throwable): ImportFailure = when {
            e is SdocxImportException -> when (e.error) {
                SdocxImportError.LEGACY_UNSUPPORTED -> ImportFailure.LEGACY
                SdocxImportError.INVALID -> ImportFailure.INVALID
                SdocxImportError.IO -> ImportFailure.IO
            }
            e is InputTooLargeException -> ImportFailure.INVALID
            e is PdfImportException -> if (e.reason == PdfImportError.PasswordProtected) ImportFailure.PROTECTED else ImportFailure.INVALID
            // `importNote` reports an unreadable container as an IOException; the stream itself was already open.
            kind == ImportKind.SSNOTE && e is java.io.IOException -> ImportFailure.INVALID
            e is java.io.IOException || e is SecurityException -> ImportFailure.IO
            else -> ImportFailure.INVALID
        }
    }
}

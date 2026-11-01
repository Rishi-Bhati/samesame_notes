package org.samesame.notes.core.data.transfer

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.data.pdf.PdfImportError
import org.samesame.notes.core.data.pdf.PdfImportException
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.data.repository.SdocxImportError
import org.samesame.notes.core.data.repository.SdocxImportException
import org.samesame.notes.core.data.transfer.NoteFileDetector.Verdict
import org.samesame.notes.core.model.NoteId
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

class NoteImportServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- detector ----

    @Test fun extensionDecidesTheKind() {
        assertThat(NoteFileDetector.classify("Hi.sdocx", "application/octet-stream", "1000316317")).isEqualTo(Verdict.Supported(ImportKind.SDOCX))
        assertThat(NoteFileDetector.classify("Plan.SSNOTE", "application/zip", null)).isEqualTo(Verdict.Supported(ImportKind.SSNOTE))
        assertThat(NoteFileDetector.classify("Scan.pdf", null, null)).isEqualTo(Verdict.Supported(ImportKind.PDF))
        assertThat(NoteFileDetector.classify(null, null, "/storage/x/Plan.ssnote")).isEqualTo(Verdict.Supported(ImportKind.SSNOTE))
    }

    @Test fun legacySdocIsRejectedEvenWithANoteMime() {
        assertThat(NoteFileDetector.classify("Old.sdoc", "application/sdoc", null)).isEqualTo(Verdict.Legacy)
    }

    @Test fun mimeIsUsedOnlyWithoutAnExtension() {
        assertThat(NoteFileDetector.classify(null, "application/sdoc", "1234")).isEqualTo(Verdict.Supported(ImportKind.SDOCX))
        assertThat(NoteFileDetector.classify(null, "application/pdf; charset=binary", "1234")).isEqualTo(Verdict.Supported(ImportKind.PDF))
        assertThat(NoteFileDetector.classify(null, "application/octet-stream", "1234")).isEqualTo(Verdict.Unsupported)
        assertThat(NoteFileDetector.classify("photo.jpg", "image/jpeg", null)).isEqualTo(Verdict.Unsupported)
        // An unknown extension falls back to the MIME type.
        assertThat(NoteFileDetector.classify("scan.bin", "application/pdf", null)).isEqualTo(Verdict.Supported(ImportKind.PDF))
    }

    @Test fun pdfTitleDropsTheExtension() {
        assertThat(NoteImportService.pdfTitle(" Scan 1.PDF ")).isEqualTo("Scan 1")
        assertThat(NoteImportService.pdfTitle("")).isEqualTo("")
    }

    // ---- service ----

    private class Calls { val made = ArrayList<String>(); var pdfTitle: String? = null; var pdfFile: File? = null; var pdfExisted = false }

    /** Only the three import calls are implemented; a stream whose text starts with an error keyword throws it. */
    private fun repo(calls: Calls): NoteRepository =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(NoteRepository::class.java)) { _, method, args ->
            try {
                when (method.name.substringBefore('-')) {
                    "importSdocx", "importNote" -> {
                        val text = (args[0] as InputStream).readBytes().decodeToString()
                        // Checked exceptions would surface as UndeclaredThrowableException through a Proxy; the
                        // repository's own exception mapping is covered by [failureMapping].
                        if (text == "corrupt") throw IllegalStateException("bad archive")
                        calls.made += "${method.name.substringBefore('-')}:$text"
                        NoteId("n${calls.made.size}").value
                    }
                    "importPdf" -> {
                        val file = (args[0] as List<*>).single() as File
                        calls.pdfFile = file; calls.pdfExisted = file.exists(); calls.pdfTitle = args[1] as String
                        if (file.readText() == "secret") throw PdfImportException(PdfImportError.PasswordProtected)
                        calls.made += "importPdf:${file.readText()}"
                        NoteId("n${calls.made.size}").value
                    }
                    else -> throw UnsupportedOperationException(method.name)
                }
            } catch (e: InvocationTargetException) { throw e.targetException }
        } as NoteRepository

    private fun src(name: String?, text: String, mime: String? = null) = ImportSource(name, mime, null) { text.byteInputStream() }

    @Test fun eachKindGoesToItsImporter() = runBlocking<Unit> {
        val calls = Calls()
        val service = NoteImportService(repo(calls), tmp.newFolder("import"))
        val summary = service.importAll(listOf(src("a.sdocx", "A"), src("b.ssnote", "B"), src("Scan.pdf", "C")))
        assertThat(calls.made).containsExactly("importSdocx:A", "importNote:B", "importPdf:C").inOrder()
        assertThat(summary.imported).hasSize(3)
        assertThat(summary.failed).isEqualTo(0)
        assertThat(calls.pdfTitle).isEqualTo("Scan")
        // The PDF was copied to a scratch file for the renderer and removed afterwards.
        assertThat(calls.pdfExisted).isTrue()
        assertThat(calls.pdfFile!!.exists()).isFalse()
    }

    @Test fun failuresAreCountedByReasonAndDoNotStopTheRun() = runBlocking<Unit> {
        val calls = Calls()
        val service = NoteImportService(repo(calls), tmp.newFolder("import"))
        val gone = ImportSource("gone.sdocx", null, null) { throw java.io.FileNotFoundException() }
        val summary = service.importAll(
            listOf(
                src("x.sdocx", "corrupt"), src("y.ssnote", "corrupt"), src("old.sdoc", "whatever"),
                src("photo.jpg", "img"), src("locked.pdf", "secret"), gone, src("ok.sdocx", "OK"),
            ),
        )
        assertThat(summary.imported).hasSize(1)
        assertThat(summary.failures).containsExactly(
            ImportFailure.INVALID, 2, ImportFailure.LEGACY, 1, ImportFailure.UNSUPPORTED, 1, ImportFailure.PROTECTED, 1, ImportFailure.IO, 1,
        )
        assertThat(summary.total).isEqualTo(7)
        assertThat(calls.made).containsExactly("importSdocx:OK")
    }

    @Test fun failureMapping() {
        fun of(kind: ImportKind, e: Throwable) = NoteImportService.failureOf(kind, e)
        assertThat(of(ImportKind.SDOCX, SdocxImportException(SdocxImportError.LEGACY_UNSUPPORTED))).isEqualTo(ImportFailure.LEGACY)
        assertThat(of(ImportKind.SDOCX, SdocxImportException(SdocxImportError.INVALID))).isEqualTo(ImportFailure.INVALID)
        assertThat(of(ImportKind.SDOCX, SdocxImportException(SdocxImportError.IO))).isEqualTo(ImportFailure.IO)
        assertThat(of(ImportKind.PDF, PdfImportException(PdfImportError.PasswordProtected))).isEqualTo(ImportFailure.PROTECTED)
        assertThat(of(ImportKind.PDF, PdfImportException(PdfImportError.TooManyPages))).isEqualTo(ImportFailure.INVALID)
        assertThat(of(ImportKind.SSNOTE, java.io.IOException("bad zip"))).isEqualTo(ImportFailure.INVALID)
        assertThat(of(ImportKind.PDF, java.io.IOException("read"))).isEqualTo(ImportFailure.IO)
    }

    @Test fun progressCoversEveryFile() = runBlocking<Unit> {
        val service = NoteImportService(repo(Calls()), tmp.newFolder("import"))
        val seen = ArrayList<String>()
        service.importAll(listOf(src("a.sdocx", "A"), src("b.sdocx", "B"))) { done, total, name -> seen += "$done/$total $name" }
        assertThat(seen).containsExactly("0/2 a.sdocx", "1/2 b.sdocx", "2/2 ").inOrder()
    }
}

package org.samesame.notes

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextDocActivityIntentTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val ownPackage = "org.samesame.notes"
    private val sampleUri = Uri.parse("content://com.example.provider/documents/test.txt")

    @Test
    fun manifestDeclaresActivitiesWithCorrectExportedFlags() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        val activities = doc.getElementsByTagName("activity")
        var textDocExported: Boolean? = null
        var noteTextDocExported: Boolean? = null
        for (i in 0 until activities.length) {
            val element = activities.item(i) as Element
            val name = element.getAttribute("android:name")
            val exported = element.getAttribute("android:exported")
            if (name == ".TextDocActivity" || name == "org.samesame.notes.TextDocActivity") {
                textDocExported = exported.toBoolean()
            }
            if (name == ".NoteTextDocActivity" || name == "org.samesame.notes.NoteTextDocActivity") {
                noteTextDocExported = exported.toBoolean()
            }
        }
        assertThat(textDocExported).isTrue()
        assertThat(noteTextDocExported).isFalse()
    }

    @Test
    fun onlyAWriteGrantMakesTheDocumentEditable() {
        val forged = Intent(Intent.ACTION_EDIT).setData(sampleUri).putExtra(TextDocActivity.EXTRA_WRITABLE, true)
        assertThat(TextDocActivity.isWritable(forged)).isFalse()

        val granted = Intent(Intent.ACTION_EDIT).setData(sampleUri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertThat(TextDocActivity.isWritable(granted)).isTrue()

        assertThat(TextDocActivity.isWritable(TextDocActivity.intent(context, sampleUri, "a.txt", writable = true))).isTrue()
        assertThat(TextDocActivity.isWritable(TextDocActivity.intent(context, sampleUri, "a.txt", writable = false))).isFalse()
    }

    @Test
    fun textDocActivityRejectsInternalExtrasAndPrivateUris() {
        val withNoteId = Intent().setData(sampleUri).putExtra(TextDocActivity.EXTRA_NOTE_ID, "note-1")
        assertThat(TextDocActivity.isValidIntent(withNoteId, ownPackage)).isFalse()

        val withFileId = Intent().setData(sampleUri).putExtra(TextDocActivity.EXTRA_LIBRARY_FILE_ID, "file-1")
        assertThat(TextDocActivity.isValidIntent(withFileId, ownPackage)).isFalse()

        val fileUri = Intent().setData(Uri.parse("file:///data/user/0/org.samesame.notes/notes/note.txt"))
        assertThat(TextDocActivity.isValidIntent(fileUri, ownPackage)).isFalse()

        val appPrivateUri = Intent().setData(Uri.parse("content://org.samesame.notes.fileprovider/notes/1"))
        assertThat(TextDocActivity.isValidIntent(appPrivateUri, ownPackage)).isFalse()

        val validExternal = Intent().setData(sampleUri)
        assertThat(TextDocActivity.isValidIntent(validExternal, ownPackage)).isTrue()
    }

    @Test
    fun noteTextDocActivityRequiresInternalTarget() {
        val emptyIntent = Intent()
        assertThat(NoteTextDocActivity.isValidIntent(emptyIntent)).isFalse()

        val noteIntent = Intent().putExtra(NoteTextDocActivity.EXTRA_NOTE_ID, "note-123")
        assertThat(NoteTextDocActivity.isValidIntent(noteIntent)).isTrue()

        val fileIntent = Intent().putExtra(NoteTextDocActivity.EXTRA_LIBRARY_FILE_ID, "file-123")
        assertThat(NoteTextDocActivity.isValidIntent(fileIntent)).isTrue()
    }

    @Test
    fun editIntentHasReadWriteFlagsAndActionEdit() {
        val intent = TextDocActivity.intent(context, sampleUri, displayName = "test.txt", writable = true)

        assertThat(intent.action).isEqualTo(Intent.ACTION_EDIT)
        assertThat(intent.component?.className).isEqualTo(TextDocActivity::class.java.name)
        assertThat(intent.data).isEqualTo(sampleUri)
        assertThat(intent.getStringExtra(TextDocActivity.EXTRA_DISPLAY_NAME)).isEqualTo("test.txt")
        assertThat(intent.getBooleanExtra(TextDocActivity.EXTRA_WRITABLE, false)).isTrue()
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isNotEqualTo(0)
    }

    @Test
    fun viewIntentHasReadFlagOnlyAndActionView() {
        val intent = TextDocActivity.intent(context, sampleUri, displayName = "test.txt", writable = false)

        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.component?.className).isEqualTo(TextDocActivity::class.java.name)
        assertThat(intent.data).isEqualTo(sampleUri)
        assertThat(intent.getStringExtra(TextDocActivity.EXTRA_DISPLAY_NAME)).isEqualTo("test.txt")
        assertThat(intent.getBooleanExtra(TextDocActivity.EXTRA_WRITABLE, true)).isFalse()
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
    }

    @Test
    fun noteTextDocActivityLibraryFileIntent() {
        val intent = NoteTextDocActivity.intent(context, libraryFileId = "lib-file-999")

        assertThat(intent.component?.className).isEqualTo(NoteTextDocActivity::class.java.name)
        assertThat(intent.getStringExtra(NoteTextDocActivity.EXTRA_LIBRARY_FILE_ID)).isEqualTo("lib-file-999")
        assertThat(intent.getBooleanExtra(NoteTextDocActivity.EXTRA_WRITABLE, false)).isTrue()
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isNotEqualTo(0)
    }

    @Test
    fun noteTextDocActivityNoteIntent() {
        val intent = NoteTextDocActivity.noteIntent(context, noteId = "note-123")

        assertThat(intent.component?.className).isEqualTo(NoteTextDocActivity::class.java.name)
        assertThat(intent.getStringExtra(NoteTextDocActivity.EXTRA_NOTE_ID)).isEqualTo("note-123")
        assertThat(intent.getBooleanExtra(NoteTextDocActivity.EXTRA_WRITABLE, false)).isTrue()
    }
}

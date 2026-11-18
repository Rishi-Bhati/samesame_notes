package org.samesame.notes

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextDocActivityIntentTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val sampleUri = Uri.parse("content://com.example.provider/documents/test.txt")

    @Test
    fun editIntentHasReadWriteFlagsAndActionEdit() {
        val intent = TextDocActivity.intent(context, sampleUri, displayName = "test.txt", writable = true)

        assertThat(intent.action).isEqualTo(Intent.ACTION_EDIT)
        assertThat(intent.component?.className).isEqualTo(TextDocActivity::class.java.name)
        assertThat(intent.data).isEqualTo(sampleUri)
        assertThat(intent.getStringExtra("org.samesame.notes.extra.TEXTDOC_NAME")).isEqualTo("test.txt")
        assertThat(intent.getBooleanExtra("org.samesame.notes.extra.TEXTDOC_WRITABLE", false)).isTrue()
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isNotEqualTo(0)
    }

    @Test
    fun viewIntentHasReadWriteFlagsAndActionView() {
        val intent = TextDocActivity.intent(context, sampleUri, displayName = "test.txt", writable = false)

        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.component?.className).isEqualTo(TextDocActivity::class.java.name)
        assertThat(intent.data).isEqualTo(sampleUri)
        assertThat(intent.getStringExtra("org.samesame.notes.extra.TEXTDOC_NAME")).isEqualTo("test.txt")
        assertThat(intent.getBooleanExtra("org.samesame.notes.extra.TEXTDOC_WRITABLE", true)).isFalse()
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isNotEqualTo(0)
    }

    @Test
    fun libraryFileIntentHasReadWriteFlagsAndComponent() {
        val intent = TextDocActivity.intent(context, libraryFileId = "lib-file-999")

        assertThat(intent.component?.className).isEqualTo(TextDocActivity::class.java.name)
        assertThat(intent.getStringExtra("org.samesame.notes.extra.TEXTDOC_FILE_ID")).isEqualTo("lib-file-999")
        assertThat(intent.getBooleanExtra("org.samesame.notes.extra.TEXTDOC_WRITABLE", false)).isTrue()
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isNotEqualTo(0)
    }

    @Test
    fun noteIntentHasComponentAndNoteIdExtra() {
        val intent = TextDocActivity.noteIntent(context, noteId = "note-123")

        assertThat(intent.component?.className).isEqualTo(TextDocActivity::class.java.name)
        assertThat(intent.getStringExtra("org.samesame.notes.extra.TEXTDOC_NOTE_ID")).isEqualTo("note-123")
        assertThat(intent.getBooleanExtra("org.samesame.notes.extra.TEXTDOC_WRITABLE", false)).isTrue()
    }
}

package org.samesame.notes.core.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.model.CustomFontFile
import org.samesame.notes.core.model.FontChoice
import org.samesame.notes.core.model.FontKind
import org.samesame.notes.core.model.FontSettings
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CustomFontStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "OTTOvalid font bytes".toByteArray()
    private var settings = FontSettings(choice = FontChoice(FontKind.Custom), customFontName = "Old")
    private val directory get() = temporary.root
    private val old get() = File(directory, CustomFontFile.storageName(settings.customFontFileName))

    private fun store(
        validate: (File) -> Boolean = { true },
        rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
        update: suspend ((FontSettings) -> FontSettings) -> Unit = { settings = it(settings) },
    ) = CustomFontStore(directory, { settings }, update, validate, rename)

    @Test fun replacementPublishesCompleteNewFileBeforeDeletingOld() = runTest {
        val previous = old.also { it.writeText("old bytes") }
        val store = store(update = { transform ->
            val next = transform(settings)
            assertThat(previous.readText()).isEqualTo("old bytes")
            assertThat(File(directory, next.customFontFileName!!).readBytes()).isEqualTo(bytes)
            settings = next
        })
        assertThat(store.import(bytes.inputStream(), "New")).isEqualTo(FontImportResult.Imported)
        assertThat(settings.customFontName).isEqualTo("New")
        assertThat(old.readBytes()).isEqualTo(bytes)
        assertThat(previous.exists()).isFalse()
        assertThat(directory.listFiles()!!.toList()).containsExactly(old)
    }

    @Test fun rejectedOrFailedRenamePreservesOldFileAndPreferences() = runTest {
        old.writeText("old bytes")
        val before = settings
        assertThat(store(validate = { false }).import(bytes.inputStream(), "Bad"))
            .isEqualTo(FontImportResult.Rejected(CustomFontFile.Verdict.NotAFont))
        assertThat(store(rename = { _, _ -> false }).import(bytes.inputStream(), "Failed"))
            .isEqualTo(FontImportResult.Unreadable)
        assertThat(settings).isEqualTo(before)
        assertThat(old.readText()).isEqualTo("old bytes")
        assertThat(directory.listFiles()!!.toList()).containsExactly(old)
    }

    @Test fun preferenceFailureRollsBackNewFile() = runTest {
        old.writeText("old bytes")
        val before = settings
        assertThat(store(update = { throw IOException("write failed") }).import(bytes.inputStream(), "Failed"))
            .isEqualTo(FontImportResult.Unreadable)
        assertThat(settings).isEqualTo(before)
        assertThat(old.readText()).isEqualTo("old bytes")
        assertThat(directory.listFiles()!!.toList()).containsExactly(old)
    }

    @Test fun overlappingImportsAndRemovalAreSerializedAndUseUniqueTemps() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val names = mutableListOf<String>()
        var writes = 0
        val store = store(validate = { names += it.name; true }, update = {
            if (++writes == 1) { entered.complete(Unit); release.await() }
            settings = it(settings)
        })
        val first = async { store.import(bytes.inputStream(), "First") }
        entered.await()
        val second = async { store.import(bytes.inputStream(), "Second") }
        val remove = launch { store.remove() }
        runCurrent()
        assertThat(names).hasSize(1)
        release.complete(Unit)
        assertThat(first.await()).isEqualTo(FontImportResult.Imported)
        assertThat(second.await()).isEqualTo(FontImportResult.Imported)
        remove.join()
        assertThat(names.distinct()).hasSize(2)
        assertThat(settings.customFontName).isNull()
        assertThat(settings.choice.kind).isEqualTo(FontKind.System)
        assertThat(directory.listFiles()).isEmpty()
    }

    @Test fun removalClearsPreferencesBeforeDeletingAndRecoversOrphans() = runTest {
        val previous = old.also { it.writeText("old bytes") }
        File(directory, "incoming-abandoned.tmp").writeText("partial")
        File(directory, "font-abandoned.ttf").writeText("unreferenced")
        val store = store(update = {
            assertThat(previous.exists()).isTrue()
            settings = it(settings)
        })
        store.remove()
        assertThat(settings.customFontFileName).isNull()
        assertThat(settings.customFontName).isNull()
        assertThat(directory.listFiles()).isEmpty()
    }

    @Test fun unavailableStorageLeavesPreferencesUnchanged() = runTest {
        val before = settings
        val unavailable = temporary.newFile("not-a-directory")
        val store = CustomFontStore(unavailable, { settings }, { settings = it(settings) }, { true })
        assertThat(store.import(bytes.inputStream(), "Failed")).isEqualTo(FontImportResult.Unreadable)
        assertThat(settings).isEqualTo(before)
    }

    @Test fun emptyAndOversizeImportsLeaveNoTemporaryFile() = runTest {
        val store = store()
        assertThat(store.import(byteArrayOf().inputStream(), "Empty"))
            .isEqualTo(FontImportResult.Rejected(CustomFontFile.Verdict.Empty))
        val oversized = ByteArray(org.samesame.notes.core.model.Limits.MAX_CUSTOM_FONT_BYTES + 1)
        bytes.copyInto(oversized)
        assertThat(store.import(oversized.inputStream(), "Large"))
            .isEqualTo(FontImportResult.Rejected(CustomFontFile.Verdict.TooLarge))
        assertThat(directory.listFiles()).isEmpty()
    }
}

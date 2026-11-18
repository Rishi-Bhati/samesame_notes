package org.samesame.notes.core.data.repository

import android.content.ContextWrapper
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.CustomFontFile
import org.samesame.notes.core.model.FontKind
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CustomFontRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun leavingTheCallerDoesNotCancelTheImportOrPublishTheWrongName() = runBlocking {
        withTimeout(10_000) {
            val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getFilesDir(): File = temporary.root
            }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val settings = object : AppSettingsRepository {
                override val settings = MutableStateFlow(AppSettings())
                override suspend fun update(transform: (AppSettings) -> AppSettings) {
                    entered.complete(Unit)
                    release.await()
                    settings.value = transform(settings.value)
                }
            }
            val application = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val repository = CustomFontRepository(context, settings, application, { true })
                val source = temporary.newFile("picked.ttf").apply { writeText("OTTOtest font") }
                val transaction = repository.import(Uri.fromFile(source), "Picked")!!
                val caller = launch { transaction.await() }
                entered.await()
                assertThat(repository.busy.value).isTrue()
                assertThat(repository.import(Uri.fromFile(source), "Second")).isNull()
                repository.remove()
                caller.cancelAndJoin()
                release.complete(Unit)
                assertThat(transaction.await()).isEqualTo(FontImportResult.Imported)
                val font = settings.settings.value.font
                assertThat(font.choice.kind).isEqualTo(FontKind.Custom)
                assertThat(font.customFontName).isEqualTo("Picked")
                val selected = File(File(temporary.root, "fonts"), CustomFontFile.storageName(font.customFontFileName))
                assertThat(selected.readBytes()).isEqualTo(source.readBytes())
                assertThat(selected.parentFile!!.listFiles()!!.toList()).containsExactly(selected)
                assertThat(repository.busy.value).isFalse()
            } finally {
                application.cancel()
            }
        }
    }
}

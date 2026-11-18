package org.samesame.notes.core.data.repository

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.samesame.notes.core.data.di.ApplicationScope
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Owns font transactions beyond the lifetime of the settings page or its view-model. */
@Singleton
class CustomFontRepository internal constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
    validate: (File) -> Boolean,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settings: AppSettingsRepository,
        @ApplicationScope scope: CoroutineScope,
    ) : this(context, settings, scope, { file -> runCatching { Typeface.Builder(file).build() }.getOrNull() != null })

    private val store = CustomFontStore(
        File(context.filesDir, "fonts"),
        read = { settings.settings.first().font },
        update = { transform -> settings.update { it.copy(font = transform(it.font)) } },
        validate = validate,
    )
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun import(uri: Uri, fallbackName: String): Deferred<FontImportResult>? {
        if (!_busy.compareAndSet(false, true)) return null
        return scope.async(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val name = runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull()?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: fallbackName
                val input = runCatching { resolver.openInputStream(uri) }.getOrNull()
                    ?: return@async FontImportResult.Unreadable
                input.use { store.import(it, name) }
            } finally {
                _busy.value = false
            }
        }
    }

    fun remove() {
        if (!_busy.compareAndSet(false, true)) return
        scope.async(Dispatchers.IO) {
            try { store.remove() } finally { _busy.value = false }
        }
    }
}

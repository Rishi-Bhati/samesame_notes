package org.samesame.notes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.samesame.notes.core.data.repository.AppSettingsRepository
import org.samesame.notes.core.data.repository.CustomFontRepository
import org.samesame.notes.core.data.repository.FontImportResult
import org.samesame.notes.core.model.CustomFontFile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.samesame.notes.core.model.AppSettings
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: AppSettingsRepository,
    private val fonts: CustomFontRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    val fontBusy = fonts.busy
    private val _fontErrors = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val fontErrors = _fontErrors.asSharedFlow()

    fun importFont(uri: android.net.Uri, fallbackName: String) {
        val transaction = fonts.import(uri, fallbackName) ?: return
        viewModelScope.launch {
            val error = when (val result = transaction.await()) {
                FontImportResult.Imported -> return@launch
                FontImportResult.Unreadable -> R.string.feature_settings_font_error_read
                is FontImportResult.Rejected -> when (result.reason) {
                    CustomFontFile.Verdict.TooLarge -> R.string.feature_settings_font_error_too_large
                    CustomFontFile.Verdict.Empty -> R.string.feature_settings_font_error_empty
                    else -> R.string.feature_settings_font_error_not_a_font
                }
            }
            _fontErrors.emit(error)
        }
    }

    fun removeFont() = fonts.remove()
}

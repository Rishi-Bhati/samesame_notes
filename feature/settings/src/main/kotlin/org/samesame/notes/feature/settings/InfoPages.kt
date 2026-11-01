package org.samesame.notes.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.cappedSp

private data class Licence(val name: String, val licence: String)

/** Libraries SameSame notes ships with. Everything here is Apache 2.0 unless stated otherwise. */
private val Licences = listOf(
    Licence("AndroidX (Activity, Core, Lifecycle, DataStore, Navigation 3, WorkManager, Hilt extensions)", "Apache License 2.0"),
    Licence("AndroidX Compose (UI, Foundation, Material 3, Material Icons)", "Apache License 2.0"),
    Licence("AndroidX Ink (brush, geometry, rendering, storage, strokes, authoring)", "Apache License 2.0"),
    Licence("AndroidX Room and SQLite", "Apache License 2.0"),
    Licence("AndroidX Biometric", "Apache License 2.0"),
    Licence("AndroidX Graphics and Input Motion Prediction", "Apache License 2.0"),
    Licence("Dagger and Hilt", "Apache License 2.0"),
    Licence("Kotlin and kotlinx.coroutines", "Apache License 2.0"),
    Licence("kotlinx.serialization", "Apache License 2.0"),
    Licence("Coil", "Apache License 2.0"),
)

@Composable
internal fun LicencesPage(onBack: () -> Unit) {
    SettingsScaffold(stringResource(R.string.feature_settings_licences), onBack) {
        InfoBody {
            Licences.forEach { l ->
                Text(l.name, fontSize = cappedSp(17f), color = SameSameTheme.colors.textPrimary, modifier = Modifier.padding(top = 16.dp))
                Text(l.licence, fontSize = cappedSp(13f), color = SameSameTheme.colors.textTertiary, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
internal fun PrivacyPage(onBack: () -> Unit) {
    val colors = SameSameTheme.colors
    SettingsScaffold(stringResource(R.string.feature_settings_privacy_notice), onBack) {
        InfoBody {
            Text(
                stringResource(R.string.feature_settings_privacy_heading), fontSize = cappedSp(17f),
                fontWeight = FontWeight.Medium, color = colors.textPrimary,
            )
            Text(
                stringResource(R.string.feature_settings_privacy_body), fontSize = cappedSp(15f), lineHeight = cappedSp(22f),
                color = colors.textSecondary, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun InfoBody(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) { content() }
}

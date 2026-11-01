package org.samesame.notes.feature.composer.panels

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.samesame.notes.core.designsystem.theme.SameSameTheme

/** Dark and light previews at the reference phone width (411 dp). */
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 411)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 411)
internal annotation class ComposerPreviews

@Composable
internal fun PreviewFrame(content: @Composable () -> Unit) {
    SameSameTheme {
        Box(Modifier.background(androidx.compose.material3.MaterialTheme.colorScheme.background).padding(vertical = 8.dp)) {
            content()
        }
    }
}

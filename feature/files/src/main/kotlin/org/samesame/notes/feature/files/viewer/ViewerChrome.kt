package org.samesame.notes.feature.files.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.feature.files.R

/** Colours of a viewer's bar: the theme's, or white on a dark scrim over an image. */
internal data class ChromeColors(val bar: Color, val content: Color, val secondary: Color)

@Composable
internal fun themeChrome(): ChromeColors {
    val colors = SameSameTheme.colors
    return ChromeColors(colors.background, colors.textPrimary, colors.textSecondary)
}

internal val OverImageChrome = ChromeColors(Color.Black.copy(alpha = 0.55f), Color.White, Color.White.copy(alpha = 0.7f))

/** The viewers' top bar: back, the file name with an optional second line, then the actions. */
@Composable
internal fun ViewerTopBar(
    title: String,
    subtitle: String?,
    chrome: ChromeColors,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().background(chrome.bar).windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(AppIcons.Back, stringResource(R.string.feature_files_back), tint = chrome.content)
        }
        Column(Modifier.weight(1f).padding(start = 4.4.dp, end = 8.dp)) {
            Text(title, style = SameSameText.ToolbarTitle.capped(), color = chrome.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = SameSameText.Caption, color = chrome.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

@Composable
internal fun ChromeIconButton(icon: ImageVector, label: String, chrome: ChromeColors, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(40.dp)) {
        Icon(icon, label, tint = chrome.content, modifier = Modifier.size(24.dp))
    }
}

/** A centred message for a viewer that can't show its file, with an "Open with" way out. */
@Composable
internal fun ViewerMessage(message: String, onOpenWith: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = SameSameTheme.colors
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(message, style = SameSameText.EmptyTitle, color = colors.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (onOpenWith != null) {
                org.samesame.notes.core.designsystem.component.AppPillButton(
                    stringResource(R.string.feature_files_more_open_with), onOpenWith, Modifier.padding(top = 20.dp),
                )
            }
        }
    }
}

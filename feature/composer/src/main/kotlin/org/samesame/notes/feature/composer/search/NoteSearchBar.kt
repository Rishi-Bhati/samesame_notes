package org.samesame.notes.feature.composer.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.richtext.TextSearch
import org.samesame.notes.feature.composer.R

/**
 * In-note search bar. It takes the place of the editor app bar (the app bar is hidden while it is open) and is
 * 56 dp high on the page background: the search field with an up (back) button, a clear button once there is text,
 * then "next" (down chevron) and "previous" (up chevron) 10 dp apart with 13 dp at the end. A disabled arrow is
 * drawn at 40% alpha. Enter jumps to the next hit.
 */
@Composable
internal fun NoteSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    canForward: Boolean,
    canBackward: Boolean,
    onForward: () -> Unit,
    onBackward: () -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SameSameTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.background), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, Modifier.padding(start = 6.dp).size(40.dp)) {
                Icon(AppIcons.Back, stringResource(R.string.feature_composer_back), Modifier.size(24.dp), tint = colors.textPrimary)
            }
            Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.feature_composer_search_placeholder), color = colors.textPrimary.copy(alpha = 0.4f), fontSize = 18.sp, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { onQueryChange(it.take(TextSearch.MAX_QUERY_LENGTH)) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 18.sp, color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, Modifier.size(40.dp)) {
                    Icon(AppIcons.Close, stringResource(R.string.feature_composer_search_clear), Modifier.size(24.dp), tint = colors.textPrimary)
                }
            }
            ArrowButton(AppIcons.ChevronDown, R.string.feature_composer_search_next, canForward, onForward, Modifier.padding(start = 10.dp))
            ArrowButton(AppIcons.ChevronUp, R.string.feature_composer_search_previous, canBackward, onBackward, Modifier.padding(start = 10.dp, end = 13.dp))
        }
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp)
                .background(if (colors.isDark) Color(0x4DDDDDDD) else Color(0x14000000)),
        )
    }
}

@Composable
private fun ArrowButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Icon(
        icon, stringResource(label),
        modifier.size(24.dp).alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        tint = SameSameTheme.colors.textPrimary,
    )
}

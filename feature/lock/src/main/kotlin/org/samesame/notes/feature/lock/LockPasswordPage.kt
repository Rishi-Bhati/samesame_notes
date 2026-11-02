package org.samesame.notes.feature.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.samesame.notes.core.data.repository.LOCK_PASSWORD_MAX_LENGTH
import org.samesame.notes.core.data.repository.LOCK_PASSWORD_LEGACY_MIN_LENGTH
import org.samesame.notes.core.data.repository.LOCK_PASSWORD_MIN_LENGTH
import org.samesame.notes.core.designsystem.component.SimpleTopBar
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.designsystem.theme.cappedSp

/**
 * The reference app's password page (layout `lock_password`): transparent toolbar with the title, a
 * centred 17sp guide text (39dp side margins, 32dp above, 22dp below), a centred 17sp single-line password
 * field (24dp side margins, 8dp vertical padding, 32dp below, max 17 characters), an optional biometrics
 * check box, and a bottom bar with "Cancel" | "Continue".
 *
 * [attempt] changes after every failed submit so the field clears; [error] is shown under the field.
 */
@Composable
internal fun LockPasswordPage(
    title: String,
    guide: String,
    primaryLabel: String,
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    attempt: Int = 0,
    error: String? = null,
    extraGuide: String? = null,
    checkboxLabel: String? = null,
    checked: Boolean = false,
    onCheckedChange: (Boolean) -> Unit = {},
    /** Shortest input accepted: new passwords need [LOCK_PASSWORD_MIN_LENGTH]; verify pages accept older, shorter ones. */
    minLength: Int = LOCK_PASSWORD_LEGACY_MIN_LENGTH,
    /**
     * The password is being checked: the primary action shows a small progress circle in its place and input is
     * ignored until the result (the reference's bottom-menu progress view).
     */
    busy: Boolean = false,
) {
    val leaving = LocalLockFlowLeaving.current
    BackHandler(enabled = !leaving) { if (!busy) onCancel() }
    val colors = SameSameTheme.colors
    val guideColor = if (colors.isDark) Color(0xFFB0B0B0) else Color(0xFF444444)
    var text by remember(attempt) { mutableStateOf("") }
    var overMax by remember(attempt) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(attempt) { runCatching { focus.requestFocus() } }

    val valid = text.length >= minLength
    val shownError = when {
        overMax -> stringResource(R.string.feature_lock_over_max, LOCK_PASSWORD_MAX_LENGTH)
        else -> error
    }
    fun submit() { if (valid && !busy && !leaving) onSubmit(text) }

    Column(modifier.fillMaxSize().background(colors.background).imePadding()) {
        SimpleTopBar(
            title = { Text(title, style = SameSameText.SearchTitle.capped(), color = colors.textPrimary, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                    Icon(AppIcons.Back, stringResource(R.string.feature_lock_back), tint = colors.textPrimary)
                }
            },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                guide, fontSize = cappedSp(17f), color = guideColor, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = 39.dp, end = 39.dp, top = 32.dp, bottom = 22.dp),
            )
            if (extraGuide != null) {
                Text(
                    extraGuide, fontSize = cappedSp(14f), color = guideColor, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 39.dp, end = 39.dp, bottom = 22.dp),
                )
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            overMax = it.length > LOCK_PASSWORD_MAX_LENGTH
                            text = it.take(LOCK_PASSWORD_MAX_LENGTH).filterNot(Char::isISOControl)
                        },
                        singleLine = true,
                        readOnly = busy || leaving,
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = cappedSp(17f), textAlign = TextAlign.Center),
                        cursorBrush = SolidColor(colors.textPrimary),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                // The field's underline (EditText backgroundTint).
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.textTertiary))
                if (shownError != null) {
                    Text(
                        shownError, fontSize = cappedSp(14f), color = colors.accent, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }
            if (checkboxLabel != null) {
                Row(
                    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCheckedChange(!checked) }
                        .padding(horizontal = 24.dp).padding(bottom = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked, onCheckedChange, colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.checkOutline))
                    Text(checkboxLabel, fontSize = cappedSp(15f), color = guideColor)
                }
            }
        }
        // Bottom bar (BottomNavigationView, 56dp): two equal text actions.
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            BottomText(stringResource(R.string.feature_lock_cancel), !busy && !leaving, Modifier.weight(1f), onCancel)
            if (busy) {
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    // Small progress circle: 24 dp, 3.5 dp stroke, 1 dp inset.
                    CircularProgressIndicator(Modifier.size(24.dp).padding(1.dp), color = colors.accent, strokeWidth = 3.5.dp, trackColor = Color.Transparent)
                }
            } else {
                BottomText(primaryLabel, valid && !leaving, Modifier.weight(1f), ::submit)
            }
        }
    }
}

@Composable
private fun BottomText(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = SameSameTheme.colors
    Box(modifier.fillMaxSize().clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            label, style = SameSameText.Button.capped(),
            color = if (enabled) colors.textPrimary else colors.textSecondary.copy(alpha = 0.5f),
        )
    }
}

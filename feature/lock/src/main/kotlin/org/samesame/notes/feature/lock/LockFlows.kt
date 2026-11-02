package org.samesame.notes.feature.lock

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.samesame.notes.core.designsystem.component.AppDialog
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.data.repository.LOCK_PASSWORD_MIN_LENGTH
import org.samesame.notes.core.security.LockToken

enum class LockAuthMode {
    /** Ask for the password; if none exists yet, run the "Create password" flow first. */
    VerifyOrCreate,

    /** Ask for the password (or biometrics); cancels immediately if no password exists. */
    VerifyOnly,
}

private enum class Step { Decide, Warn, Create1, Create2, Biometric, Password }

/**
 * Full-screen credential flow: yields a [LockToken] through [onToken] (the caller closes it) or calls
 * [onCancel]. See `docs/checkpoints/locked-notes.md` (UI FLOW) for the screens.
 *
 * - no password and [LockAuthMode.VerifyOrCreate]: "remember your password" dialog -> Create password
 *   (+ "Also let me unlock with fingerprints" when the device has biometrics) -> confirm
 * - password set: BiometricPrompt first when enabled (and [allowBiometrics]), "USE PASSWORD" or any failure
 *   falls back to the Confirm password page
 */
@Composable
fun LockAuthFlow(
    mode: LockAuthMode,
    onToken: (LockToken) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    allowBiometrics: Boolean = true,
    viewModel: LockViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasPassword by viewModel.hasPassword.collectAsStateWithLifecycle(initialValue = null)
    var step by rememberSaveable { mutableStateOf(Step.Decide) }
    var firstPassword by remember { mutableStateOf("") }
    var wantBiometrics by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var lockedOut by remember { mutableStateOf(false) }
    var fingerprintsChanged by remember { mutableStateOf(false) }
    var throttledMs by remember { mutableStateOf(0L) }
    // A password is being checked (key derivation takes a moment by design): progress instead of the primary action.
    var busy by remember { mutableStateOf(false) }
    val incorrect = stringResource(R.string.feature_lock_incorrect)
    val throttled = if (throttledMs > 0) lockoutMessage(throttledMs) else null
    val mismatch = stringResource(R.string.feature_lock_do_not_match)
    val resetToast = stringResource(R.string.feature_lock_fingerprints_changed_reset)
    val bioTitle = stringResource(R.string.feature_lock_biometric_title)
    val bioSubtitle = stringResource(R.string.feature_lock_biometric_subtitle)
    val bioNegative = stringResource(R.string.feature_lock_use_password)

    fun failed(message: String) { error = message; attempt++; busy = false }

    LaunchedEffect(hasPassword) {
        val has = hasPassword ?: return@LaunchedEffect
        if (step != Step.Decide) return@LaunchedEffect
        step = when {
            has -> {
                // Keystore calls (and a config read) are binder / disk work: off the main thread.
                if (allowBiometrics && withContext(Dispatchers.Default) { viewModel.biometricUnlockCipher() } != null) Step.Biometric
                else {
                    if (viewModel.consumeBiometricReset()) {
                        Toast.makeText(context, resetToast, Toast.LENGTH_LONG).show()
                        fingerprintsChanged = true
                    }
                    Step.Password
                }
            }
            mode == LockAuthMode.VerifyOrCreate -> Step.Warn
            else -> { onCancel(); Step.Decide }
        }
    }

    // BiometricPrompt: re-create the cipher (it is single use) and show the prompt once per entry.
    LaunchedEffect(step) {
        if (step != Step.Biometric) return@LaunchedEffect
        val activity = context.findFragmentActivity()
        val cipher = withContext(Dispatchers.Default) { viewModel.biometricUnlockCipher() }
        if (activity == null || cipher == null) {
            if (viewModel.consumeBiometricReset()) {
                Toast.makeText(context, resetToast, Toast.LENGTH_LONG).show()
                fingerprintsChanged = true
            }
            step = Step.Password
            return@LaunchedEffect
        }
        activity.promptBiometric(cipher, bioTitle, bioSubtitle, bioNegative) { outcome ->
            when (outcome) {
                is BiometricOutcome.Success -> scope.launch {
                    val token = runCatching { viewModel.completeBiometricUnlock(outcome.cipher) }.getOrNull()
                    if (token != null) onToken(token) else step = Step.Password
                }
                BiometricOutcome.LockedOut -> { lockedOut = true; step = Step.Password }
                BiometricOutcome.UsePassword -> step = Step.Password
            }
        }
    }

    val lockoutText = if (lockedOut) stringResource(R.string.feature_lock_lockout) else null
    when (step) {
        Step.Decide, Step.Biometric -> Box(modifier.fillMaxSize().background(SameSameTheme.colors.background))

        Step.Warn -> {
            Box(modifier.fillMaxSize().background(SameSameTheme.colors.background))
            AppDialog(
                onDismissRequest = onCancel,
                dismissLabel = stringResource(R.string.feature_lock_cancel),
                confirmLabel = stringResource(R.string.feature_lock_ok),
                onConfirm = { step = Step.Create1 },
            ) {
                Text(
                    stringResource(R.string.feature_lock_remember_password),
                    style = SameSameText.DialogMessage.capped(), color = SameSameTheme.colors.textPrimary,
                )
            }
        }

        Step.Create1 -> LockPasswordPage(
            title = stringResource(R.string.feature_lock_create_title),
            guide = stringResource(R.string.feature_lock_guide_create, LOCK_PASSWORD_MIN_LENGTH),
            primaryLabel = stringResource(R.string.feature_lock_continue),
            onCancel = onCancel,
            onSubmit = { firstPassword = it; error = null; attempt++; step = Step.Create2 },
            minLength = LOCK_PASSWORD_MIN_LENGTH,
            attempt = attempt,
            checkboxLabel = if (remember { viewModel.biometricsAvailable() }) stringResource(R.string.feature_lock_use_fingerprints) else null,
            checked = wantBiometrics,
            onCheckedChange = { wantBiometrics = it },
        )

        Step.Create2 -> LockPasswordPage(
            title = stringResource(R.string.feature_lock_create_title),
            guide = stringResource(R.string.feature_lock_guide_confirm),
            primaryLabel = stringResource(R.string.feature_lock_ok),
            onCancel = { firstPassword = ""; error = null; attempt++; step = Step.Create1 },
            onSubmit = { again ->
                if (again != firstPassword) {
                    failed(mismatch)
                } else scope.launch {
                    busy = true
                    val token = runCatching { viewModel.createPassword(again) }.getOrNull()
                    firstPassword = ""
                    if (token == null) { failed(mismatch); return@launch }
                    if (wantBiometrics) {
                        // Switching biometrics on needs one authenticated prompt on the new Keystore key.
                        enrollThen(context.findFragmentActivity(), viewModel, token, scope, bioTitle, bioSubtitle, bioNegative) { onToken(token) }
                    } else {
                        onToken(token)
                    }
                }
            },
            attempt = attempt,
            error = error,
            minLength = LOCK_PASSWORD_MIN_LENGTH,
            busy = busy,
        )

        Step.Password -> LockPasswordPage(
            title = stringResource(R.string.feature_lock_confirm_title),
            guide = stringResource(R.string.feature_lock_guide_default),
            primaryLabel = stringResource(R.string.feature_lock_continue),
            onCancel = onCancel,
            onSubmit = { pw ->
                busy = true
                scope.launch {
                    when (val r = viewModel.verify(pw)) {
                        is VerifyResult.Ok -> { throttledMs = 0; onToken(r.token) }
                        VerifyResult.Wrong -> {
                            // Tell the user as soon as the next attempt is throttled.
                            throttledMs = viewModel.lockoutRemainingMs()
                            failed(incorrect)
                        }
                        is VerifyResult.LockedOut -> { throttledMs = r.remainingMs; failed(incorrect); error = null }
                    }
                }
            },
            attempt = attempt,
            error = throttled ?: error,
            extraGuide = lockoutText ?: if (fingerprintsChanged) stringResource(R.string.feature_lock_fingerprints_changed_guide) else null,
            busy = busy,
        )
    }
}

/** "Too many incorrect attempts. Try again in N seconds/minutes." for a lockout of [remainingMs]. */
@Composable
internal fun lockoutMessage(remainingMs: Long): String {
    val seconds = ((remainingMs + 999) / 1000).toInt().coerceAtLeast(1)
    return if (seconds < 60) pluralStringResource(R.plurals.feature_lock_try_again_seconds, seconds, seconds)
    else ((seconds + 59) / 60).let { pluralStringResource(R.plurals.feature_lock_try_again_minutes, it, it) }
}

/** Runs the one-time biometric enrolment prompt, then [then] regardless of the outcome (the password stays valid). */
private fun enrollThen(
    activity: androidx.fragment.app.FragmentActivity?,
    viewModel: LockViewModel,
    token: LockToken,
    scope: kotlinx.coroutines.CoroutineScope,
    title: String,
    subtitle: String,
    negative: String,
    then: () -> Unit,
) {
    val cipher = viewModel.biometricEnrollCipher()
    if (activity == null || cipher == null) { then(); return }
    activity.promptBiometric(cipher, title, subtitle, negative) { outcome ->
        scope.launch {
            if (outcome is BiometricOutcome.Success) runCatching { viewModel.completeBiometricEnroll(token, outcome.cipher) }
            then()
        }
    }
}

/**
 * Settings > Note unlock methods > Biometrics switch on: verify the password, then authenticate once on the
 * new Keystore key. [onDone] gets true when biometrics are now on.
 */
@Composable
fun EnableBiometricsFlow(onDone: (Boolean) -> Unit, modifier: Modifier = Modifier, viewModel: LockViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.feature_lock_biometric_title)
    val subtitle = stringResource(R.string.feature_lock_biometric_subtitle)
    val negative = stringResource(R.string.feature_lock_cancel)
    LockAuthFlow(
        mode = LockAuthMode.VerifyOnly,
        allowBiometrics = false,
        modifier = modifier,
        onCancel = { onDone(false) },
        onToken = { token ->
            val cipher = viewModel.biometricEnrollCipher()
            val activity = context.findFragmentActivity()
            if (cipher == null || activity == null) { token.close(); onDone(false); return@LockAuthFlow }
            activity.promptBiometric(cipher, title, subtitle, negative) { outcome ->
                scope.launch {
                    val ok = outcome is BiometricOutcome.Success &&
                        runCatching { viewModel.completeBiometricEnroll(token, outcome.cipher) }.isSuccess
                    token.close()
                    onDone(ok)
                }
            }
        },
    )
}

private enum class ChangeStep { Old, New, Confirm }

/** Settings > Change password: current password, new password, confirm. Rewraps the master key only. */
@Composable
fun ChangePasswordFlow(onDone: () -> Unit, modifier: Modifier = Modifier, viewModel: LockViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(ChangeStep.Old) }
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val incorrect = stringResource(R.string.feature_lock_incorrect)
    val mismatch = stringResource(R.string.feature_lock_do_not_match)
    val title = stringResource(R.string.feature_lock_change_title)
    var throttledMs by remember { mutableStateOf(0L) }
    val throttled = if (throttledMs > 0) lockoutMessage(throttledMs) else null
    val finish = { old = ""; new = ""; onDone() }

    when (step) {
        ChangeStep.Old -> LockPasswordPage(
            title = title, guide = stringResource(R.string.feature_lock_guide_current),
            primaryLabel = stringResource(R.string.feature_lock_continue), onCancel = finish,
            onSubmit = { pw ->
                scope.launch {
                    when (val r = viewModel.verify(pw)) {
                        is VerifyResult.Ok -> { r.token.close(); throttledMs = 0; old = pw; error = null; attempt++; step = ChangeStep.New }
                        VerifyResult.Wrong -> { throttledMs = viewModel.lockoutRemainingMs(); error = incorrect; attempt++ }
                        is VerifyResult.LockedOut -> { throttledMs = r.remainingMs; error = null; attempt++ }
                    }
                }
            },
            attempt = attempt, error = throttled ?: error, modifier = modifier,
        )
        ChangeStep.New -> LockPasswordPage(
            title = title, guide = stringResource(R.string.feature_lock_guide_create, LOCK_PASSWORD_MIN_LENGTH),
            primaryLabel = stringResource(R.string.feature_lock_continue), onCancel = finish,
            onSubmit = { new = it; error = null; attempt++; step = ChangeStep.Confirm },
            attempt = attempt, modifier = modifier, minLength = LOCK_PASSWORD_MIN_LENGTH,
        )
        ChangeStep.Confirm -> LockPasswordPage(
            title = title, guide = stringResource(R.string.feature_lock_guide_confirm),
            primaryLabel = stringResource(R.string.feature_lock_ok),
            onCancel = { error = null; attempt++; step = ChangeStep.New },
            onSubmit = { again ->
                if (again != new) { error = mismatch; attempt++ }
                else scope.launch {
                    if (viewModel.changePassword(old, new)) finish() else { error = incorrect; attempt++; step = ChangeStep.Old }
                }
            },
            attempt = attempt, error = error, modifier = modifier, minLength = LOCK_PASSWORD_MIN_LENGTH,
        )
    }
}

/** "Unlock note?" / "Unlock N notes?" with Cancel / "Remove lock". */
@Composable
fun UnlockConfirmDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        dismissLabel = stringResource(R.string.feature_lock_cancel),
        confirmLabel = stringResource(R.string.feature_lock_remove_lock),
        onConfirm = onConfirm,
        atBottom = true,
    ) {
        Text(
            pluralStringResource(R.plurals.feature_lock_unlock_title, count, count),
            style = SameSameText.DialogMessage.capped(), color = SameSameTheme.colors.textPrimary,
        )
    }
}

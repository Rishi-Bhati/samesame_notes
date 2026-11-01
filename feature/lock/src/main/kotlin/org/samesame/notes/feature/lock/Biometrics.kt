package org.samesame.notes.feature.lock

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor
import javax.crypto.Cipher

internal fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

internal sealed interface BiometricOutcome {
    class Success(val cipher: Cipher) : BiometricOutcome

    /** The user chose "USE PASSWORD", cancelled, or the sensor failed; fall back to the password page. */
    data object UsePassword : BiometricOutcome

    data object LockedOut : BiometricOutcome
}

/** Shows a BiometricPrompt bound to [cipher] (Keystore key that needs a strong biometric per use). */
internal fun FragmentActivity.promptBiometric(
    cipher: Cipher,
    title: String,
    subtitle: String,
    negative: String,
    onOutcome: (BiometricOutcome) -> Unit,
) {
    val executor = Executor { Handler(Looper.getMainLooper()).post(it) }
    val prompt = BiometricPrompt(
        this, executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                onOutcome(if (c != null) BiometricOutcome.Success(c) else BiometricOutcome.UsePassword)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onOutcome(
                    when (errorCode) {
                        BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricOutcome.LockedOut
                        else -> BiometricOutcome.UsePassword
                    },
                )
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setNegativeButtonText(negative)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setConfirmationRequired(false)
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
}

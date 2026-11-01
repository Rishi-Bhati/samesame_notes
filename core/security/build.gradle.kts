plugins {
    alias(libs.plugins.samesame.jvm.library)
}

// Pure-JVM crypto for locked notes: PBKDF2-SHA256 key derivation, AES-GCM envelopes, the password vault.
// The Android Keystore / BiometricPrompt part is isolated behind BiometricKeyStore (implemented in :core:data).

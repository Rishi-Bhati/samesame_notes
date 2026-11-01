plugins {
    alias(libs.plugins.samesame.android.feature)
}

android {
    namespace = "org.samesame.notes.feature.lock"
}

dependencies {
    // BiometricPrompt (CryptoObject-bound Keystore key); needs a FragmentActivity host.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.activity.compose)
    api(projects.core.security)
}

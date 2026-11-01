plugins {
    alias(libs.plugins.samesame.android.feature)
}

android {
    namespace = "org.samesame.notes.feature.composer"
}

dependencies {
    implementation(projects.core.ink)
    implementation(projects.core.richtext)
    implementation(projects.feature.lock)
    implementation(projects.feature.share)
    implementation(projects.feature.camera)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)

    // Compose UI tests need a newer Espresso than ui-test pulls in (the old one calls InputManager.getInstance, gone on API 37).
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

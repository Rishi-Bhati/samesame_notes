plugins {
    alias(libs.plugins.samesame.android.library)
    alias(libs.plugins.samesame.android.compose)
}

android {
    namespace = "org.samesame.notes.core.richtext"
}

dependencies {
    api(projects.core.model)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)

    // Debug host screen lives in src/androidTest (test APK only, never merged into the app):
    // ./gradlew :core:richtext:installDebugAndroidTest, then launch RichTextDebugActivity.
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.androidx.compose.material3)

    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Newer Espresso than ui-test pulls in (the old one calls InputManager.getInstance, gone on API 37).
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

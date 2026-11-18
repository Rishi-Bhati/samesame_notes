plugins {
    alias(libs.plugins.samesame.android.library)
    alias(libs.plugins.samesame.android.compose)
}

android {
    namespace = "org.samesame.notes.core.richtext"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.designsystem)
    // Markdown (CommonMark + GFM tables, strikethrough, autolinks, task lists). Heading anchors are not needed: the
    // preview never links to a heading id.
    api(libs.commonmark.core)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.task.list.items)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)

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

plugins {
    alias(libs.plugins.samesame.android.library)
    alias(libs.plugins.samesame.android.compose)
}

android {
    namespace = "org.samesame.notes.core.ink"
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.ink.authoring.compose)
    api(libs.androidx.ink.brush)
    api(libs.androidx.ink.brush.compose)
    api(libs.androidx.ink.geometry)
    api(libs.androidx.ink.rendering)
    api(libs.androidx.ink.strokes)
    implementation(libs.androidx.ink.storage)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)

    androidTestImplementation(libs.truth)
}

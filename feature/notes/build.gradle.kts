plugins {
    alias(libs.plugins.samesame.android.feature)
}

android {
    namespace = "org.samesame.notes.feature.notes"
}

dependencies {
    implementation(projects.feature.lock)
    implementation(projects.feature.share)
    implementation(libs.androidx.activity.compose)
}

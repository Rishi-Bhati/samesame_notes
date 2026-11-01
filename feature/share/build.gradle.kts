plugins {
    alias(libs.plugins.samesame.android.feature)
}

android {
    namespace = "org.samesame.notes.feature.share"
}

dependencies {
    implementation(libs.androidx.activity.compose)
}

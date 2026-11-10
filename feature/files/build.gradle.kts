plugins {
    alias(libs.plugins.samesame.android.feature)
}

android {
    namespace = "org.samesame.notes.feature.files"
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
}

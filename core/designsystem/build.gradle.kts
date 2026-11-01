plugins {
    alias(libs.plugins.samesame.android.library)
    alias(libs.plugins.samesame.android.compose)
}

android {
    namespace = "org.samesame.notes.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.compose.ui)
    api(libs.oneui.icons)
    implementation(libs.androidx.activity.compose)
}

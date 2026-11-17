plugins {
    alias(libs.plugins.samesame.android.feature)
}

android {
    namespace = "org.samesame.notes.feature.widgets"
}

dependencies {
    // Home-screen widgets (note creator, single note, note list): Compose-style API that builds RemoteViews.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
}

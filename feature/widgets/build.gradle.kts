plugins {
    alias(libs.plugins.samesame.android.feature)
}

android {
    namespace = "org.samesame.notes.feature.widgets"
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
}

dependencies {
    // Home-screen widgets (note creator, single note, note list): Compose-style API that builds RemoteViews.
    implementation(libs.androidx.glance.appwidget)
    // Glance runs every widget session as a WorkManager job. Glance only asks for WorkManager 2.7.1 (2021), which runs the
    // session in-process without holding a JobScheduler job, so Android 14+'s cached-app freezer could freeze the app mid
    // draw: widgets stuck on the loading spinner. Current WorkManager keeps the job open while the session runs.
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
}

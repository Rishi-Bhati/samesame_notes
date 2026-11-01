plugins {
    alias(libs.plugins.samesame.android.library)
    alias(libs.plugins.samesame.android.room)
    alias(libs.plugins.samesame.hilt)
}

android {
    namespace = "org.samesame.notes.core.database"
}

dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.coroutines.android)
    // The DAO tests run real Room on the JVM against Robolectric's SQLite (no driver set).
    testImplementation(libs.robolectric)
}

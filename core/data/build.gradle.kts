plugins {
    alias(libs.plugins.samesame.android.library)
    alias(libs.plugins.samesame.hilt)
}

android {
    namespace = "org.samesame.notes.core.data"
}

dependencies {
    api(projects.core.model)
    api(projects.core.document)
    api(projects.core.export)
    implementation(projects.core.sdocx)
    implementation(projects.core.database)
    implementation(projects.core.files)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    // Repository tests run real Room on the JVM against Robolectric's SQLite (no driver set).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.runtime)
}

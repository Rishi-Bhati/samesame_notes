plugins {
    alias(libs.plugins.samesame.android.library)
}

// The Files library's storage layer (docs/architecture/files-feature.md 1.3): content-addressed blob store, import
// staging, MIME sniffing and the read-only content provider. No UI, no database and no Hilt (the provider is declared
// in :core:data as a subclass that resolves LibraryFileLookup through a Hilt entry point).
android {
    namespace = "org.samesame.notes.core.files"
}

dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.robolectric)
}

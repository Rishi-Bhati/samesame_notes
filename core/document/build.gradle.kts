plugins {
    alias(libs.plugins.samesame.android.library)
    alias(libs.plugins.samesame.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.samesame.notes.core.document"
}

dependencies {
    api(projects.core.model)
    api(projects.core.ink)
    api(projects.core.security)
    // The .sdocx stroke decoder is pure JVM; this module owns the androidx.ink bridge for it.
    implementation(projects.core.sdocx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}

dependencies {
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

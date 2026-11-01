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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}

dependencies {
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

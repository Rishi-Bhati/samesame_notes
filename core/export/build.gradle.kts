plugins {
    alias(libs.plugins.samesame.android.library)
}

android {
    namespace = "org.samesame.notes.core.export"
}

dependencies {
    api(projects.core.model)
    api(projects.core.ink)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

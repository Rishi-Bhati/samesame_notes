plugins {
    alias(libs.plugins.samesame.android.feature)
}

android {
    namespace = "org.samesame.notes.feature.textdoc"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.richtext)
    implementation(projects.feature.share)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.robolectric)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
}

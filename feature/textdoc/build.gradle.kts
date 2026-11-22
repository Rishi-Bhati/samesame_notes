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
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
}

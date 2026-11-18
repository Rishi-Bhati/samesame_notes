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
    implementation(libs.pdfbox.android)

    // Markdown (CommonMark + GFM tables, strikethrough, autolinks, task lists)
    implementation(libs.commonmark.core)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.task.list.items)
    implementation(libs.commonmark.ext.autolink)

    testImplementation(libs.robolectric)

    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
}

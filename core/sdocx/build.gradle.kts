plugins {
    alias(libs.plugins.samesame.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// Pure-JVM `.sdocx` reader/writer/mapper. No Android dependencies: the container is plain ZIP
// (java.util.zip) and hashes are MessageDigest. Depends only on :core:model so the mapping layer
// can produce org.samesame.notes.core.model types. androidx.ink bridging (once stroke payloads are
// decoded) must live in an Android adapter outside this module.
dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.serialization.json)
}

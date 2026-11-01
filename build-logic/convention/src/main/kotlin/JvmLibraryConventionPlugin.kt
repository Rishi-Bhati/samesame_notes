import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.samesame.buildlogic.configureKotlinJvm
import org.samesame.buildlogic.library
import org.samesame.buildlogic.libs

/** Pure Kotlin module with no Android dependency (fast unit tests, reusable off-device). */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        configureKotlinJvm()

        dependencies {
            add("testImplementation", libs.library("junit"))
            add("testImplementation", libs.library("truth"))
        }
    }
}

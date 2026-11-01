import androidx.room.gradle.RoomExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.samesame.buildlogic.library
import org.samesame.buildlogic.libs

class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("androidx.room")
        pluginManager.apply("com.google.devtools.ksp")

        // Exported schemas are committed so migrations can be tested against them.
        extensions.configure<RoomExtension> {
            schemaDirectory("$projectDir/schemas")
        }

        dependencies {
            add("implementation", libs.library("androidx-room-runtime"))
            add("implementation", libs.library("androidx-room-ktx"))
            add("implementation", libs.library("androidx-sqlite-bundled"))
            add("ksp", libs.library("androidx-room-compiler"))
            add("androidTestImplementation", libs.library("androidx-room-testing"))
        }
    }
}

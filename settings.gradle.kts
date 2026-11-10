// Repository content filters (supply chain): Google's Maven serves only Google/AndroidX groups, and those groups are
// never taken from Maven Central or the Plugin Portal, so a look-alike artifact published there cannot be picked up.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral {
            content {
                excludeGroupByRegex("com\\.android.*")
                excludeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal {
            content {
                excludeGroupByRegex("com\\.android.*")
                excludeGroupByRegex("androidx.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral {
            content {
                excludeGroupByRegex("com\\.android.*")
                excludeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "samesame-notes"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

include(":core:model")
include(":core:designsystem")
include(":core:database")
include(":core:ink")
include(":core:document")
include(":core:data")
include(":core:richtext")
include(":core:export")
include(":core:sdocx")
include(":core:security")
include(":core:files")

include(":feature:notes")
include(":feature:composer")
include(":feature:settings")
include(":feature:lock")
include(":feature:share")
include(":feature:camera")
include(":feature:files")

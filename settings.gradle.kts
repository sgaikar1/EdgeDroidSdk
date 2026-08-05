pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "EdgeDroidSdk"
include(":edgedroid-common")
include(":edgedroid-core")
include(":edgedroid-api")
include(":edgedroid-storage")
include(":edgedroid-download")
include(":runtime-llama")
include(":sample-app")

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
        // Vendored ONNX Runtime GenAI Android AAR (not on a public Maven repo). See
        // repo/README.md and THIRD_PARTY_NOTICES.md.
        maven { url = uri("$rootDir/repo") }
    }
}

rootProject.name = "EdgeDroidSdk"
include(":edgedroid-common")
include(":edgedroid-core")
include(":edgedroid-api")
include(":edgedroid-storage")
include(":edgedroid-download")
include(":runtime-llama")
include(":runtime-onnx")
include(":sample-app")

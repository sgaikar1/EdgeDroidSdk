import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
    id("signing")
}

android {
    namespace = "com.sgaikar1.edgedroid.runtime.llama"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_METAL=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_BLAS=OFF",
                    "-DGGML_CUBLAS=OFF",
                    "-DGGML_VULKAN=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_CURL=OFF",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(project(":edgedroid-core"))
    implementation(project(":edgedroid-common"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}


mavenPublishing {
    configure(AndroidSingleVariantLibrary("release", sourcesJar = true, publishJavadocJar = false))
    publishToMavenCentral(host = com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    coordinates("io.github.sgaikar1", project.name, "0.1.0")
    pom {
        name.set("EdgeDroid ${project.name}")
        description.set("EdgeDroid: on-device LLM SDK for Android - ${project.name} module")
        url.set("https://github.com/sgaikar1/EdgeDroidSdk")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("sgaikar1")
                name.set("Santosh Gaikar")
                email.set("santoshgaikar1@gmail.com")
            }
        }
        scm {
            url.set("https://github.com/sgaikar1/EdgeDroidSdk")
            connection.set("scm:git:https://github.com/sgaikar1/EdgeDroidSdk.git")
            developerConnection.set("scm:git:ssh://github.com/sgaikar1/EdgeDroidSdk.git")
        }
        issueManagement {
            url.set("https://github.com/sgaikar1/EdgeDroidSdk/issues")
        }
    }
}

val signingKey = providers.gradleProperty("SIGNING_KEY").orNull ?: System.getenv("SIGNING_KEY")
val signingPassword = providers.gradleProperty("SIGNING_PASSWORD").orNull ?: System.getenv("SIGNING_PASSWORD")
if (signingKey != null && signingPassword != null) {
    signing {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
}


apply(from = rootProject.file("gradle/javadoc-jar.gradle.kts"))

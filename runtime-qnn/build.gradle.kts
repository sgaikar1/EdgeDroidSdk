import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.sgaikar1.edgedroid.runtime.qnn"
    compileSdk = 35

    defaultConfig {
        // The Qualcomm GenieX AAR declares minSdk 27 (Android 8.1) in its manifest.
        minSdk = 27
        buildConfigField("String", "SDK_VERSION", "\"${libs.versions.sdkVersion.get()}\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs {
            // GenieX ships its native libraries inside the AAR (arm64-v8a only); keep them all.
            useLegacyPackaging = true
        }
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(project(":edgedroid-core"))
    implementation(project(":edgedroid-common"))
    implementation(libs.kotlinx.coroutines.android)
    // Qualcomm GenieX Android SDK (QNN / Hexagon NPU + llama.cpp + VLM runtimes).
    // Published on Maven Central: https://central.sonatype.com/artifact/com.qualcomm.qti/geniex-android
    implementation(libs.geniex.android)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary("release", sourcesJar = true, publishJavadocJar = false))
    signAllPublications()
    publishToMavenCentral(host = com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    coordinates("io.github.sgaikar1", project.name, libs.versions.sdkVersion.get())
    pom {
        name.set("EdgeDroid ${project.name}")
        description.set("EdgeDroid: on-device LLM SDK for Android - ${project.name} module (Qualcomm Hexagon NPU / QNN via GenieX)")
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

apply(from = rootProject.file("gradle/javadoc-jar.gradle.kts"))
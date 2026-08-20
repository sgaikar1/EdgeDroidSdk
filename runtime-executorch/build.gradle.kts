import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.sgaikar1.edgedroid.runtime.executorch"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        buildConfigField("String", "SDK_VERSION", "\"${libs.versions.sdkVersion.get()}\"")
        ndk {
            // ExecuTorch's official Android AAR (org.pytorch:executorch-android) ships exactly
            // these two ABIs (~7 MB total). Keep this filter in sync with
            // ExecuTorchPlugin.supportedAbis so consuming apps never accidentally pull a wider
            // (or universal) native blob.
            abiFilters += listOf("arm64-v8a", "x86_64")
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

    packaging {
        jniLibs {
            // libexecutorch.so is already ABI-scoped by the dependency AAR; never merge it into
            // a fat universal .so.
            useLegacyPackaging = false
        }
    }
}

dependencies {
    api(project(":edgedroid-core"))
    implementation(project(":edgedroid-common"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.executorch.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary("release", sourcesJar = true, publishJavadocJar = false))
    signAllPublications()
    publishToMavenCentral(host = com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    coordinates("io.github.sgaikar1", project.name, libs.versions.sdkVersion.get())
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

apply(from = rootProject.file("gradle/javadoc-jar.gradle.kts"))
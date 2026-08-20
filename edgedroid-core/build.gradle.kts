import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        publishLibraryVariants("release")
    }
    // Declare each iOS target exactly once and reuse the references for the framework config.
    val iosTargets = listOf(iosArm64(), iosSimulatorArm64())

    // Export an iOS framework so an Xcode app can consume the SPI (spike proof: this must link).
    iosTargets.forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "EdgeDroidCore"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":edgedroid-common"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
        }
        androidUnitTest.dependencies {
            implementation(libs.junit)
        }
    }
}

android {
    namespace = "com.sgaikar1.edgedroid.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

mavenPublishing {
    configure(com.vanniktech.maven.publish.KotlinMultiplatform(sourcesJar = true, javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty()))
    signAllPublications()
    publishToMavenCentral(host = com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    coordinates("io.github.sgaikar1", project.name, libs.versions.sdkVersion.get())
    pom {
        name.set("EdgeDroid ${project.name}")
        description.set("EdgeDroid: on-device LLM SDK for Android and iOS - ${project.name} module (Kotlin Multiplatform)")
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

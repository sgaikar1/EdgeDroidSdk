import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":edgedroid-common"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
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

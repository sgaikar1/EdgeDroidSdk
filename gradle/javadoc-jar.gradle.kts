// Generates a minimal but non-empty `-javadoc.jar` and attaches it to the vanniktech
// publication ("maven") for Android modules. AGP/Dokka javadoc cannot parse Kotlin sealed
// classes on this toolchain, so we ship a valid HTML doc stub instead of an empty jar.
// Applied via `apply(from = rootProject.file("gradle/javadoc-jar.gradle.kts"))`.

import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

val docDir = layout.buildDirectory.dir("generated/edgedroid-javadoc")

val generateJavadocStub by tasks.registering {
    inputs.property("module", project.name)
    outputs.dir(docDir)
    doLast {
        val dir = docDir.get().asFile
        dir.mkdirs()
        dir.resolve("index.html").writeText(
            """
            |<!DOCTYPE html>
            |<html lang="en">
            |<head><meta charset="utf-8"><title>EdgeDroid ${project.name}</title></head>
            |<body>
            |<h1>EdgeDroid ${project.name}</h1>
            |<p>On-device LLM SDK for Android. API details are in the module sources jar.</p>
            |</body>
            |</html>
            """.trimMargin(),
        )
    }
}

val edgedroidJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    archiveBaseName.set(project.name)
    dependsOn(generateJavadocStub)
    from(docDir)
}

afterEvaluate {
    val publishing = extensions.getByType(PublishingExtension::class.java)
    publishing.publications.named("maven", MavenPublication::class.java) {
        artifact(edgedroidJavadocJar)
    }
}

# Vendored Maven artifacts

This directory hosts third-party Maven artifacts that EdgeDroid depends on but that are **not
available on a public Maven repository**. They are committed to the repo so builds are
reproducible and offline-friendly.

## ONNX Runtime GenAI (Android AAR)

- **Artifact:** `com.microsoft.onnxruntime:onnxruntime-genai-android:0.15.2`
- **Files:**
  - `onnxruntime-genai-android-0.15.2.aar` — pulled from the official
    [v0.15.2 GitHub release](https://github.com/microsoft/onnxruntime-genai/releases/tag/v0.15.2)
  - `onnxruntime-genai-android-0.15.2.pom` — hand-written minimal POM (groupId/artifactId/version
    only; the AAR carries no transitive dependencies)
- **License:** MIT (see `THIRD_PARTY_NOTICES.md`)
- **Why vendored:** Microsoft publishes the GenAI Java/Android bindings only as GitHub release
  assets, not to Maven Central.
- **Updating:** download the newer `.aar` from the release page, replace the file here, bump
  `onnxruntimeGenAi` in `gradle/libs.versions.toml`, and refresh this POM if coordinates change.

> Note: this AAR ships native libs for `arm64-v8a` and `x86_64` only (no `armeabi-v7a`). The
> GenAI chat path therefore requires one of those ABIs.

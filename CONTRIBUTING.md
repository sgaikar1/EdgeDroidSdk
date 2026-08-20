# Contributing to EdgeDroid

Thanks for wanting to contribute! 🚀 EdgeDroid is an on-device LLM SDK for Android built
around a **runtime plugin SPI**: llama.cpp is just one interchangeable backend, and adding
ExecuTorch, LiteRT, MNN or a brand-new runtime is an *additive* module, not a refactor.

There are many ways to help: fixing bugs, improving docs, adding a runtime, better sampling,
a new chat template, tests, or the sample app.

Please read our [Code of Conduct](CODE_OF_CONDUCT.md) first.

## Prerequisites

- JDK 17
- Android Studio (this project uses **AGP 8.13 / Gradle 8.13 / Kotlin 2.1.21**)
- Android SDK (compileSdk 35), NDK 28.x and CMake 3.22.1 (only needed for native work)
- macOS native tools for the Vulkan backend:

  ```sh
  brew install shaderc spirv-headers vulkan-headers ninja
  ```

## Setup

```sh
git clone https://github.com/sgaikar1/EdgeDroidSdk.git
cd EdgeDroidSdk
git submodule update --init --recursive   # llama.cpp is a submodule
```

Open the project in Android Studio and let it sync, or build from the CLI:

```sh
./gradlew build            # assemble all modules + run unit tests
./gradlew :sample-app:assembleDebug
```

## Project structure

| Module | Purpose |
| --- | --- |
| `:edgedroid-common` | Tokens, options, results, log seam, formats |
| `:edgedroid-core` | **The SPI** — `Runtime`, `RuntimePlugin`, `LlmEngine`, `ChatSession`, `Downloader`, `ModelStorage`, `ModelProvider`, capabilities. Zero native/network deps. |
| `:edgedroid-api` | `EdgeDroid` + builder, `RuntimeRegistry`, `RuntimeSelector` |
| `:edgedroid-storage` | app-private layout + `metadata.json` |
| `:edgedroid-download` | OkHttp downloader (resume/verify) + the foreground download service |
| `:runtime-llama` | llama.cpp (submodule) + JNI + `LlamaPlugin` — the only module that knows native code |
| `:runtime-onnx` | ONNX Runtime + `OnnxPlugin` (embeddings, no-KV LLM, vision) |
| `:runtime-executorch` | ExecuTorch (`org.pytorch:executorch-android`) + `ExecuTorchPlugin` (PTE, streaming, vision) |
| `:sample-app` | Jetpack Compose demo: runtime/model picker, HF browser, chat + image attach |

**A rule of thumb:** the SDK core (`:edgedroid-common` / `:edgedroid-core` / `:edgedroid-api`)
should never know which runtime, model format, or native bridge exists. Keep it that way.

## Adding a runtime (the main contribution path)

1. Create a new module (e.g. `:runtime-executorch`) with `api(project(":edgedroid-core"))`.
2. Implement the two interfaces:

   ```kotlin
   class ExecPlugin : RuntimePlugin {
       override val id = "executorch"
       override val supportedFormats = setOf(ModelFormat.PTE)
       override val capabilities = setOf(Capability.STREAMING)
       override val supportedAbis = setOf("arm64-v8a", "x86_64")
       override suspend fun create(config: RuntimeConfig): Runtime = ExecRuntime(config)
   }
   ```

3. Wire it into the sample or tests via `sdk.registerRuntime(ExecPlugin())` /
   `Runtime.AUTO`.
4. **No changes to the SDK core.** If you find yourself adding `if (runtime == ...)`
   anywhere in core, that's a design smell — raise it in the PR.

## Conventions

- **Kotlin official style**, 4-space indentation, `kotlin.code.style=official`.
- **No comments unless they explain *why*.** Code should read clearly on its own.
- Keep `:edgedroid-core` free of Android framework, network, and native dependencies.
- Add **unit tests** for new logic (JVM tests run in `./gradlew build`). Pure helpers
  (samplers, tokenizers, prompt parsers) should be testable without a device.
- Run `./gradlew build` locally before pushing.

## Pull request workflow

1. Fork the repo and create a branch (`feature/...`, `fix/...`).
2. Make your change, with tests where relevant.
3. Run `./gradlew build` — it must be green.
4. Open a PR. In the description: what changed, why, and (for native/runtime work) what you
   verified on-device.

## Releasing

Maintainers only:

1. Bump `sdkVersion` in `gradle/libs.versions.toml`.
2. `./gradlew publishAllPublicationsToMavenCentralRepository` (requires the signing key +
   Central Portal token).
3. Verify the portal shows **PUBLISHED**, then `git tag vX.Y.Z && git push origin main --tags`.

## Questions?

Open an issue or start a discussion on GitHub.

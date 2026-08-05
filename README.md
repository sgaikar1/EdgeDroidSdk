# EdgeDroid SDK

An on-device LLM SDK for Android, architected around a **runtime plugin SPI** so that
llama.cpp is just one interchangeable backend — ExecuTorch, LiteRT, MNN or anything else can
be added later as a new module with zero changes to the SDK core.

```
Android App
   │
LLM SDK (single Kotlin API)
   │
Runtime Registry
   │
┌────────┬─────────┬─────────┐
│ llama  │ Exec    │ LiteRT  │   ← every runtime is a RuntimePlugin
└────────┴─────────┴─────────┘
```

The SDK owns **everything except inference**: downloads, storage, runtime selection, sessions,
prompt rendering, threading, model formats. Developers never touch JNI, GGUF, paths, or
runtime initialization.

## Modules

| Module | Package | Responsibility |
| --- | --- | --- |
| `:edgedroid-common` | `com.sgaikar1.edgedroid.common` | Tokens, options, results, log seam, formats |
| `:edgedroid-core` | `com.sgaikar1.edgedroid.core` | **The SPI** — `Runtime`, `RuntimePlugin`, `LlmEngine`, `ChatSession`, `Downloader`, `ModelStorage`, `ModelProvider`, capabilities |
| `:edgedroid-api` | `com.sgaikar1.edgedroid.api` | `LlmSdk` + builder, `RuntimeRegistry`, `RuntimeSelector` |
| `:edgedroid-storage` | `com.sgaikar1.edgedroid.storage` | app-private layout + `metadata.json` |
| `:edgedroid-download` | `com.sgaikar1.edgedroid.download` | OkHttp downloader with resume/verify |
| `:runtime-llama` | `com.sgaikar1.edgedroid.runtime.llama` | llama.cpp (submodule) + JNI + `LlamaPlugin` |
| `:sample-app` | `com.sgaikar1.edgedroid.sample` | Compose demo: download + streaming chat |

`:edgedroid-core` has **zero native or network dependencies**. The only module that knows
JNI/llama.cpp exists is `:runtime-llama`.

## Getting started

```kotlin
val sdk = LlmSdk.Builder(context)
    .runtime(Runtime.plugin(LlamaPlugin()))   // or Runtime.AUTO once registered
    .model(
        Model.remote(
            id = "smollm2-135m",
            url = "https://huggingface.co/unsloth/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf",
        ),
    )
    .threading { threads(4); batchThreads(4) }
    .memory { contextSize(2048); mmap(true); batchSize(256) }
    .download { maxRetries(3); timeout(60.seconds) }
    .build()

sdk.models.download()          // Flow<ModelDownloadState> for progress
sdk.load()                     // downloads first if missing, then loads
sdk.stream("Hello!") { token -> print(token) }
// or use a local file directly:
sdk.loadModel(File("/storage/emulated/0/models/qwen.gguf"))
```

## Adding a runtime (the whole point)

Implement the SPI, ship it as a module, register it — nothing in the core changes:

```kotlin
class ExecPlugin : RuntimePlugin {
    override val id = "executorch"
    override val supportedFormats = setOf(ModelFormat.PTE)
    override val capabilities = setOf(Capability.STREAMING)
    override suspend fun create(config: RuntimeConfig): Runtime = ExecRuntime(config)
}

sdk.registerRuntime(ExecPlugin())
```

`RuntimeSelectorTest` in `:edgedroid-api` proves AUTO selection dispatches purely on
`RuntimePlugin` metadata. The SDK never checks `if (runtime == ...)`.

## Storage layout (app-private, no permissions)

```
files/
  models/     verified final models + metadata.json
  downloads/  partial downloads (survive pause/resume via HTTP Range)
  cache/      reusable artifacts
  temp/       scratch
```

## Native build

llama.cpp is a git submodule pinned at `b10285` under `runtime-llama/src/main/cpp/llama.cpp`.
Built with Gradle NDK + CMake (CPU only, arm64-v8a + x86_64). After cloning:

```
git submodule update --init --recursive
```

## Requirements

- Android Studio (AGP 8.13 / Gradle 8.13 / Kotlin 2.0.21)
- JDK 17
- NDK 28.x, SDK CMake 3.22.1
- minSdk 26

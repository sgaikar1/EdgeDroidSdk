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

Published on **Maven Central** as `io.github.sgaikar1` (version `0.5.0`). SDK Kotlin footprint
is ~170 KB; the llama.cpp AAR is ~3.9 MB (arm64 + x86_64).

## Installation

Add Maven Central (already present in most new Android projects) and the two dependencies
you actually need:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.sgaikar1:edgedroid-api:0.5.0")
    implementation("io.github.sgaikar1:runtime-llama:0.5.0") // or any other runtime plugin
}
```

> The `api`/`runtime` scopes in the POM pull in `edgedroid-core`, `edgedroid-common`,
> `edgedroid-storage` and `edgedroid-download` transitively — you only declare these two.
> Requires `minSdk 26`.

## Quick start

```kotlin
val sdk = LlmSdk.Builder(context)
    .runtime(Runtime.plugin(LlamaPlugin()))     // register the llama.cpp runtime
    .model(
        Model.remote(
            id = "smollm2-135m",
            name = "SmolLM2 135M Instruct (Q4_K_M)",
            url = "https://huggingface.co/unsloth/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf",
            sizeBytes = 105_454_144L,
            metadata = mapOf("template" to "chatml"),
        ),
    )
    .threading { threads(4); batchThreads(4) }
    .memory { contextSize(2048); mmap(true); batchSize(256) }
    .download { maxRetries(3); timeout(60.seconds) }
    .build()

// 1. Download the model (idempotent; you can observe progress — see below)
sdk.models.download()

// 2. Load it into the runtime (downloads first if missing)
sdk.load()

// 3. Stream a completion
sdk.stream("Summarize this text") { token ->
    print(token.text)  // tokens arrive as they are generated
}
```

## Builder configuration

| Method | What it configures |
| --- | --- |
| `.runtime(spec)` | `Runtime.AUTO`, `Runtime.byId("llama")`, or `Runtime.plugin(...)` — how the runtime is selected |
| `.model(model)` | The model to download/load (`Model.remote(...)` or `Model.local(path)`). Optional if you call `loadModel(File)` later |
| `.threading { }` | `threads`, `batchThreads` — compute parallelism |
| `.memory { }` | `contextSize`, `batchSize`, `mmap`, `gpu(...)` — context / memory / GPU budget |
| `.download { }` | `maxRetries`, `connectTimeout`, `readTimeout`, `timeout`, `chunkBuffer` — downloader behaviour |
| `.logging(provider)` | Plug in your logger via the `LogProvider` fun interface |
| `.registerRuntime(plugin)` | Register an additional runtime before building |

## GPU acceleration

The SDK uses the **GPU automatically when a Vulkan device is available, otherwise CPU** —
no configuration needed. On Android that's llama.cpp's Vulkan backend; it coexists with the
CPU backend, which handles whatever layers aren't offloaded.

```kotlin
// Default — nothing to do:
val sdk = LlmSdk.Builder(context).model(...).build()

// Or be explicit:
val sdk = LlmSdk.Builder(context)
    .model(...)
    .memory { gpu(GPU.AUTO) }          // detect Vulkan; offload all layers if present
    .build()
```

| Policy | Behaviour |
| --- | --- |
| `GPU.AUTO` (default) | Offload all layers to the GPU when a Vulkan device is detected, else CPU |
| `GPU.CPU` | Always CPU |
| `GPU.ALL` | Offload all layers (falls back to CPU if loading fails) |
| `GPU.Layers(n)` | Offload exactly `n` layers (0 = CPU) |

How it works:

- At runtime init the SDK asks llama.cpp how many GPU (Vulkan) devices were actually
  enumerated — this is authoritative, not a guess from device properties.
- If loading with GPU layers fails on a device with a broken driver, the SDK retries once
  on CPU and logs a warning.
- `sdk.models.checkCompatibility()` warns (`no_vulkan_gpu`) if you explicitly requested GPU
  offload but the device reports no Vulkan support.

## Private / gated models

Attach auth headers to every download request for gated/private model files (Hugging Face
gated repos, Git LFS, corporate storage):

```kotlin
val sdk = LlmSdk.Builder(context)
    .model(Model.remote(id = "gated-model", url = "https://huggingface.co/…/model.gguf"))
    .download {
        header("Authorization", "Bearer hf_…")   // or headers(mapOf(...)) to replace
    }
    .build()
```

- Headers are sent on the **initial and resumed** requests, so pause/resume keeps auth.
- They are **never logged or persisted** — they live only in `DownloadConfig` (not in
  `Model.metadata` or `metadata.json`).
- `header(k, v)` adds/overrides one entry; `headers(map)` replaces the whole set.
- OkHttp strips `Authorization` on **cross-host redirects**; for hosts that redirect to a
  signed CDN, put the token in the URL instead (`Model.downloadUrl` already supports that).

## Checking device compatibility before downloading

Ask the SDK whether this device can actually run the model **before** committing to a
large download:

```kotlin
val report: CompatibilityReport = sdk.models.checkCompatibility()

if (report.isDownloadable) {
    sdk.models.download()   // proceed
} else {
    report.errors.forEach { showError(it.message) }   // e.g. "Not enough storage: need 1.2 GB, have 400 MB free."
}
```

What is checked:

| Check | Severity | Blocks? |
| --- | --- | --- |
| Free storage >= model size + 256 MB headroom | `ERROR` | Yes — download is refused before any network request |
| A registered runtime supports the model format/capabilities | `ERROR` | Yes |
| Model file present or has a `downloadUrl` | `ERROR` | Yes (load) |
| Model large relative to device RAM | `WARNING` | No |
| Runtime native ABI missing from `Build.SUPPORTED_ABIS` | `WARNING` | No |
| CPU core count | `INFO` | No |

The same check runs automatically again right before `load()` (hard errors throw a clear
message instead of a late native crash), and `download()` fails fast with
`ModelDownloadState.Failed(kind = "compatibility", …)` if it can never succeed.

## Downloading with progress

`download()` returns a cold `Flow<ModelDownloadState>` that already runs the download;
collect it to observe state:

```kotlin
viewModelScope.launch {
    sdk.models.download().collect { state ->
        when (state) {
            is ModelDownloadState.Downloading -> progressBar.progress = state.progress
            is ModelDownloadState.Completed    -> onReady(state.localPath)
            is ModelDownloadState.Failed       -> onError("${state.kind}: ${state.message}")
            else -> Unit
        }
    }
}
```

Downloads are manager-scoped: partial files survive interruption and resume via HTTP `Range`
requests, and every file is verified against its `sha256` before it is stored. No storage
permission is needed (everything lives under the app-private files dir).

## Generating text

Streaming (preferred for latency):

```kotlin
val tokens: Flow<Token> = sdk.stream("Tell me a haiku")
sdk.stream("Tell me a haiku") { token -> appendToUi(token.text) }
```

Non-streaming:

```kotlin
val answer: String = sdk.generate("Tell me a haiku", GenerationOptions(maxTokens = 512))
```

Generation options: `temperature`, `topK`, `topP`, `minP`, `maxTokens`, `repeatPenalty`,
`seed`, `stopSequences`.

Stop an in-flight generation:

```kotlin
sdk.stop()
```

## Error handling

The SDK fails fast with explicit, catchable errors — never with native crashes.

**Context window exceeded** (`0.3.0+`). If a rendered prompt (system prompt + history +
your message) plus the requested `maxTokens` exceeds the configured `contextSize`, generation
throws a `RuntimeException`:

```
prompt of 812 tokens exceeds the context window (n_ctx=2048, maxTokens=256)
```

Handle it by trimming the conversation and retrying:

```kotlin
try {
    sdk.stream("Summarize this") { token -> /* … */ }
} catch (e: RuntimeException) {
    if (e.message?.contains("context window") == true) {
        sdk.resetChat()                 // clear history (and system prompt)
        sdk.systemPrompt = SHORT_PROMPT // optionally re-set a shorter prompt
        // retry
    }
}
```

Tips for staying inside the window:
- Use `.memory { contextSize(…) }` generously (prompt + `maxTokens` + 1 must fit).
- Keep the `systemPrompt` concise; trim old history in long conversations (auto-trimming is
  planned; for now call `resetChat()`).
- A prompt alone is decoded in chunks and never crashes regardless of length — the limit is
  the context window, not the batch size.

**Load errors.** `load()` throws if the model cannot be loaded on this device (see
[compatibility](#checking-device-compatibility-before-downloading)) or if the GGUF is invalid.

**Download errors.** `download()` never throws — it emits `ModelDownloadState.Failed(kind, message)`:
- `compatibility` — refused before any network request (e.g. insufficient storage).
- `verification` — `sha256` mismatch after download (file discarded).
- `network` / `io` / `http` — transport failures.

```kotlin
sdk.models.download().collect { state ->
    when (state) {
        is ModelDownloadState.Failed -> {
            when (state.kind) {
                "verification" -> notifyUser("Download corrupted; retrying…")
                else -> notifyUser(state.message)
            }
        }
        is ModelDownloadState.Completed -> startChat(state.localPath)
        else -> Unit
    }
}
```

**Unsupported features.** Calls outside a runtime's declared capabilities (e.g. embeddings on
the llama runtime) throw `UnsupportedOperationException`. Check `RuntimePlugin.capabilities` /
`sdk.models.checkCompatibility(requiredCapabilities = …)` up front if you depend on them.

## Using a model already on the device

```kotlin
val model: Model = sdk.loadModel(File("/storage/emulated/0/models/my-model.gguf"))
```

No download, no metadata required — the SDK adopts the file, records it, and loads it.

## Sessions, system prompt, history

The SDK keeps a chat session for you. Each `stream`/`generate` call appends the user turn and
the assistant reply to history, so follow-ups have context:

```kotlin
sdk.systemPrompt = "You are a concise assistant."
sdk.stream("What is the capital of France?") { /* … */ }
sdk.stream("And its population?") { /* … */ }   // has context

sdk.resetChat()   // clear history + system prompt
```

## Managing models

```kotlin
sdk.models.available()          // List<Model> already stored on device
sdk.models.resolve("smollm2-135m")
sdk.models.delete("smollm2-135m")   // unloads + removes file + metadata
```

## Observing SDK state

```kotlin
sdk.state.collect { state ->   // Idle → Loading → Ready → Generating …
    when (state) { /* update UI */ }
}
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
// or with Runtime.AUTO, a PTE model would now auto-select ExecPlugin
```

`RuntimeSelectorTest` in `:edgedroid-api` proves AUTO selection dispatches purely on
`RuntimePlugin` metadata. The SDK never checks `if (runtime == ...)`.

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

## Storage layout (app-private, no permissions)

```
files/
  models/     verified final models + metadata.json
  downloads/  partial downloads (survive pause/resume via HTTP Range)
  cache/      reusable artifacts
  temp/       scratch
```

## Sample app

`sample-app/` is a Jetpack Compose app that demonstrates the full flow: download with
progress bar, load, streaming chat UI, stop and clear. Run it and press **Download → Load →
Send**.

## Building from source

llama.cpp is a git submodule pinned at `b10285` under `runtime-llama/src/main/cpp/llama.cpp`.
Built with Gradle NDK + CMake (**CPU + Vulkan GPU**, arm64-v8a + x86_64). The Vulkan backend
compiles GLSL shaders at build time, which requires host tools on macOS:

```
brew install shaderc spirv-headers vulkan-headers ninja
```

After cloning:

```
git submodule update --init --recursive
./gradlew :sample-app:assembleDebug
```

### Publishing a new release

```
./gradlew publishAllPublicationsToMavenCentralRepository   # needs signingInMemoryKey + portal token
git tag v0.1.1 && git push origin main --tags
```

## Requirements

- Android Studio (AGP 8.13 / Gradle 8.13 / Kotlin 2.1.21)
- JDK 17
- NDK 28.x, SDK CMake 3.22.1 (only for building the native module)
- minSdk 26

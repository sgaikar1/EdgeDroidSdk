# EdgeDroid

**On-device LLMs for Android — one simple API, any inference engine.**

EdgeDroid is a Kotlin SDK that runs large language models **fully on your device** — private,
offline, free of server costs. It's built around a **runtime plugin SPI**, so llama.cpp is just
one interchangeable backend: ExecuTorch, LiteRT, MNN or a brand-new runtime can be dropped in
as a new module with **zero changes to the SDK core**.

📦 **MIT License** · 🛰 **Maven Central** · 🤖 **minSdk 26**

```
Your Android App
       │  one Kotlin API (EdgeDroid)
       ▼
   EdgeDroid SDK      ← owns downloads, storage, sessions, prompts,
   (common/core/api)     streaming, threading, runtime selection
       │
 Runtime Registry + AUTO
       ▼
┌────────┬─────────┬─────────┐
│ llama  │ ONNX    │ future  │   ← every runtime is a RuntimePlugin
└────────┴─────────┴─────────┘
```

> The SDK owns **everything except inference**. You never touch JNI, GGUF, model paths, or
> runtime initialization — you just talk to `EdgeDroid`.

## ✨ Features

- **llama.cpp** — GGUF chat models with **Vulkan GPU** (auto fallback to CPU)
- **Image → text** 🖼️ — attach a photo, ask "what's in this picture?" (SmolVLM / LLaVA-style)
- **ONNX Runtime** — embeddings, no-KV LLM generation, `pixel_values` vision
- **Reliable downloads** — foreground service keeps big model downloads alive in the
  background (progress notification + pause/resume + sha256 verification)
- **Private/gated models** — auth headers for Hugging Face gated repos, Git LFS, corporate storage
- **Compatibility check** — "can this device run this model?" before you download 500 MB
- **Capability system** — streaming, vision, embeddings, tool-calling, JSON, grammar
- **Sample app** with a **Hugging Face model browser**, runtime picker, and chat UI

## 📦 Installation

Add Maven Central (usually already present) and the dependencies you need:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.sgaikar1:edgedroid-api:0.8.0")
    implementation("io.github.sgaikar1:runtime-llama:0.8.0")   // llama.cpp
    // or implementation("io.github.sgaikar1:runtime-onnx:0.8.0") // ONNX Runtime
}
```

> `edgedroid-core`, `-common`, `-storage` and `-download` are pulled in transitively —
> you only declare the two coordinates above. Requires **minSdk 26**.

## 📦 Size

The Kotlin SDK itself is tiny — the bulk is the native inference engine. An Android APK only
ships the **one ABI** for the device, so the real on-device cost is about half the AAR's size.

| Artifact | AAR (all ABIs) | Notes |
| --- | --- | --- |
| `edgedroid-common` / `core` / `api` / `storage` / `download` | ~0.2 MB total | pure Kotlin |
| `runtime-llama` | ~39 MB | llama.cpp + Vulkan + vision (mmproj); ~30 MB per-ABI in an APK |
| `runtime-onnx` | ~0.1 MB | ONNX Runtime `.so`s come from the onnxruntime-android dependency |

A chat-only app adding `edgedroid-api` + `runtime-llama` adds roughly **~30 MB** to the APK.

## 🚀 Quick start

```kotlin
val sdk = EdgeDroid.Builder(context)
    .runtime(Runtime.plugin(LlamaPlugin()))     // the llama.cpp runtime
    .model(
        Model.remote(
            id = "smollm2-135m",
            url = "https://huggingface.co/unsloth/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf",
            sizeBytes = 105_454_144L,
            metadata = mapOf("template" to "chatml"),
        ),
    )
    .threading { threads(4); batchThreads(4) }
    .memory { contextSize(2048); mmap(true); batchSize(256) }
    .download { maxRetries(3); timeout(60.seconds) }
    .build()

sdk.models.download()                      // download (progress optional)
sdk.load()                                 // load into the runtime
sdk.stream("Summarize this text") { token ->
    print(token.text)                      // tokens as they're generated
}
```

That's it — no JNI, no paths, no model format knowledge.

## ⚙️ Builder configuration

| Method | What it configures |
| --- | --- |
| `.runtime(spec)` | `Runtime.AUTO`, `Runtime.byId("llama")`, or `Runtime.plugin(...)` |
| `.model(model)` | `Model.remote(...)` (download) or `Model.local(path)`. Optional if you call `loadModel(File)` later |
| `.threading { }` | `threads`, `batchThreads` |
| `.memory { }` | `contextSize`, `batchSize`, `mmap`, `gpu(...)` |
| `.download { }` | `maxRetries`, `connectTimeout`, `readTimeout`, `timeout`, `chunkBuffer`, `foregroundService`, `notification`, `header(...)` |
| `.extra(key, value)` | runtime-specific knobs (e.g. ONNX `executionProvider`) |
| `.logging(provider)` | plug in your logger via the `LogProvider` interface |
| `.registerRuntime(plugin)` | register another runtime |

## 💬 Generating text

```kotlin
// Streaming (preferred for latency):
val tokens: Flow<Token> = sdk.stream("Tell me a haiku")
sdk.stream("Tell me a haiku") { token -> appendToUi(token.text) }

// Non-streaming:
val answer: String = sdk.generate("Tell me a haiku", GenerationOptions(maxTokens = 512))

// Options: temperature, topK, topP, minP, maxTokens, repeatPenalty, seed, stopSequences
// Stop mid-generation:
sdk.stop()
```

The SDK keeps a **chat session** for you — follow-ups have context:

```kotlin
sdk.systemPrompt = "You are a concise assistant."
sdk.stream("What is the capital of France?") { /* … */ }
sdk.stream("And its population?") { /* … */ }   // has context
sdk.resetChat()                                  // clear history + system prompt
```

## 🖼️ Image → text

Attach a photo and ask a vision-capable model (SmolVLM, LLaVA, etc.):

```kotlin
sdk.generate(
    "What is in this picture?",
    images = listOf(PromptAttachment(bytes, "image/jpeg")),
) { token -> /* … */ }
```

- **llama.cpp**: the runtime loads the model's image encoder (`mmproj`) automatically when you
  provide `Model.metadata["mmprojPath"]`; `PromptAttachment` flows into the vision pipeline.
- **ONNX**: models with a `pixel_values` input get the decoded, normalized image tensor.
- `checkCompatibility(requiredCapabilities = [VISION])` verifies the selected runtime supports it.

## 🔢 Embeddings (ONNX)

```kotlin
val sdk = EdgeDroid.Builder(context)
    .runtime(Runtime.plugin(OnnxPlugin()))
    .model(Model.remote(id = "minilm", url = "…/model_quantized.onnx", format = ModelFormat.ONNX,
        metadata = mapOf("tokenizerPath" to "/path/to/tokenizer.json")))
    .build()

sdk.load()
val v: FloatArray = sdk.embeddings("A cat sits on a mat.")   // 384-dim vector
```

## ⬇️ Downloads — even when the app is backgrounded

Model downloads run in an SDK-provided **foreground service** (`dataSync`) with a progress
notification + cancel action, so they survive backgrounding, Doze, and process reclamation.
Partial files resume via HTTP `Range`; every file is sha256-verified.

```kotlin
sdk.models.download().collect { state ->
    when (state) {
        is ModelDownloadState.Downloading -> progressBar.progress = state.progress
        is ModelDownloadState.Completed    -> onReady(state.localPath)
        is ModelDownloadState.Failed       -> onError("${state.kind}: ${state.message}")
        else -> Unit
    }
}
```

- Add `POST_NOTIFICATIONS` (Android 13+) at runtime to see the notification; without it the
  service still runs.
- Configure it: `.download { foregroundService(false) }` or `.download { notification("my_channel", "Models", "Downloading") }`.
- Calling `download()` on an already-downloaded model completes instantly (no network).

### Private / gated models

```kotlin
.download { header("Authorization", "Bearer hf_…") }   // or headers(mapOf(...))
```

Headers are sent on every request (including resumes), never logged or persisted. OkHttp
strips `Authorization` on cross-host redirects — for CDN-redirecting hosts use a pre-signed URL.

## 🩺 Check compatibility before you download

```kotlin
val report: CompatibilityReport = sdk.models.checkCompatibility()
if (report.isDownloadable) sdk.models.download()
else report.errors.forEach { showError(it.message) }
```

| Check | Blocks? |
| --- | --- |
| Free storage ≥ model size + 256 MB headroom | ✅ yes (before any network) |
| A registered runtime supports the format/capabilities | ✅ yes |
| Model present or has a `downloadUrl` | ✅ yes (load) |
| Model large vs device RAM | ⚠️ warn |
| Runtime ABI missing from the device | ⚠️ warn |
| CPU core count | ℹ️ info |

## 🧩 Runtimes

| Runtime | Formats | Capabilities | Notes |
| --- | --- | --- | --- |
| `runtime-llama` | GGUF | STREAMING, VISION | CPU + Vulkan GPU auto-fallback, mmproj image→text |
| `runtime-onnx` | ONNX | STREAMING, EMBEDDINGS, VISION | Prebuilt `.so` (no NDK build); embeddings + no-KV LLM |

**Add your own** — implement the SPI, register it, done:

```kotlin
class ExecPlugin : RuntimePlugin {
    override val id = "executorch"
    override val supportedFormats = setOf(ModelFormat.PTE)
    override val capabilities = setOf(Capability.STREAMING)
    override suspend fun create(config: RuntimeConfig): Runtime = ExecRuntime(config)
}
sdk.registerRuntime(ExecPlugin())   // AUTO now routes PTE models to it
```

No `if (runtime == ...)` anywhere — the SDK dispatches purely on plugin metadata.

## 🎮 GPU acceleration

Automatic: **Vulkan GPU when available, otherwise CPU**. Override with `.memory { gpu(GPU.CPU) }`,
`GPU.ALL`, or `GPU.Layers(n)`. The SDK detects real Vulkan devices at runtime and retries on CPU
if a driver fails.

## 📱 Sample app

`sample-app/` is a Jetpack Compose demo that shows everything:

- **Runtime + model picker** (Settings screen) — llama.cpp / ONNX, with a **Hugging Face
  model browser** to search and download any GGUF/ONNX model
- **Chat** with live token streaming, a **Reasoning** area for thinking models, and a
  **Thinking…** indicator
- **Image attach** for vision models, **Embeddings** for ONNX, **Download / Load / Unload**,
  compatibility check, creativity (temperature/top-p/top-k) and system prompt

## 🔧 Building from source

llama.cpp is a git submodule pinned at `b10285` under `runtime-llama/src/main/cpp/llama.cpp`.
Native prerequisites (macOS):

```sh
brew install shaderc spirv-headers vulkan-headers ninja
git submodule update --init --recursive
./gradlew :sample-app:assembleDebug
```

Publishing a release (maintainers):

```sh
./gradlew publishAllPublicationsToMavenCentralRepository   # needs signing key + portal token
git tag v0.8.0 && git push origin main --tags
```

## 🗺️ Future scope & where you can help

EdgeDroid is early and the SPI is designed to make new work **additive**. These are the areas
we want to grow — all great places to contribute:

| Area | Details |
| --- | --- |
| **More runtimes** | ExecuTorch (PTE), LiteRT/TFLite, MNN — each is a new `RuntimePlugin` module, no core changes |
| **ONNX LLM + KV cache** | raw-ORT generation is no-KV today; full KV-cache chat needs ONNX Runtime GenAI |
| **Tool calling / function calling** | reserved `Capability.TOOL_CALLING`; runtime-agnostic tool loop + JSON parsing |
| **Structured output / grammar** | `Capability.JSON_MODE` / `GRAMMAR` — llama.cpp grammar support is available but not wired |
| **Smarter sessions** | auto-trim chat history to the context window instead of manual `resetChat()` |
| **More chat templates** | ChatML, Qwen, Llama, raw exist — add Gemma, Mistral, Phi, etc. via `PromptProcessor` |
| **Reasoning display** | the sample splits reasoning vs answer for `<|reasoning_start|>`/`think` models — more marker conventions welcome |
| **iOS / KMP** | the SPI is platform-agnostic; a Kotlin Multiplatform port of common/core + a Metal runtime is the natural next step |
| **Vision breadth** | more VLMs, batched images, per-model `pixel_values` params |
| **Perf & GPU** | device-specific Vulkan tuning, better defaults, NNAPI/GPU delegates |

Pick one, open an issue to discuss, and see [CONTRIBUTING.md](CONTRIBUTING.md).

## 🤝 Contributing

EdgeDroid is open source — contributions are welcome! Bug fixes, docs, new runtimes, better
sampling, chat templates, tests. See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, conventions,
and the "add a runtime" walkthrough. Please read the [Code of Conduct](CODE_OF_CONDUCT.md).

## ⚖️ License

[MIT](LICENSE) © 2026 Santosh Gaikar. The bundled llama.cpp is also MIT-licensed.

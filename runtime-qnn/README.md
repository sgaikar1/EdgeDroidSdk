# EdgeDroid `runtime-qnn` — Qualcomm Hexagon NPU (QNN / GenieX)

Runs GGUF and QNN-compiled models on the **Qualcomm Hexagon NPU** (and CPU/GPU fallback) using
Qualcomm's open-source [GenieX](https://github.com/qualcomm/GenieX) Android SDK — the same engine
behind the **~164 tok/s on LFM2.5-230M / ~3,700 tok/s prefill** numbers on Snapdragon 8 Elite.

The module is a plain [`RuntimePlugin`](../edgedroid-core/src/main/java/com/sgaikar1/edgedroid/core/RuntimePlugin.kt):
no SDK-core changes are needed to register or select it.

## Dependency

The Qualcomm GenieX Android SDK is published on **Maven Central** (`com.qualcomm.qti:geniex-android`).
The `runtime-qnn` AAR does **not** bundle GenieX — it depends on it, so you only need the two
coordinates below:

```kotlin
// settings.gradle.kts — Maven Central (usually already present)
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}

// app/build.gradle.kts
dependencies {
    implementation("io.github.sgaikar1:edgedroid-api:0.9.0")
    implementation("io.github.sgaikar1:runtime-qnn:0.9.0") // pulls in geniex-android transitively
}
```

> **minSdk 27** (Android 8.1) — required by the GenieX AAR manifest. The rest of EdgeDroid is
> minSdk 26, but apps using `runtime-qnn` must be 27+.
>
> Only the **arm64-v8a** ABI is supported (GenieX ships arm64 natives only).

## Registering the runtime

```kotlin
val sdk = EdgeDroid.Builder(context)
    .registerRuntime(QnnPlugin(context))   // AUTO now routes GGUF/QNN models to it
    // or pin explicitly:
    // .runtime(Runtime.byId("qnn"))
    .model(
        Model.remote(
            id = "smollm2-135m",
            url = "…/SmolLM2-135M-Instruct-Q4_K_M.gguf",   // GGUF → GenieX llama_cpp runtime
            format = ModelFormat.GGUF,
        ),
    )
    .build()
```

`RuntimeSelector` picks the QNN runtime automatically for `ModelFormat.GGUF` and
`ModelFormat.QNN` models once it is registered. Because selection is purely
format/capability-driven, registration order decides GGUF AUTO ties — register `QnnPlugin`
first (or use `Runtime.byId("qnn")`) to prefer the NPU path over `runtime-llama`.

### Registering only on capable devices

`QnnPlugin` exposes the same shape of capability flags the SDK reports for Vulkan
(`DeviceCapabilities.vulkanSupported`):

```kotlin
val qnn = QnnPlugin(context)
if (qnn.hexagonArch.isHexagon) {          // v73/v75/v77/v79/v81 detected at runtime
    builder.registerRuntime(qnn)          // NPU + CPU/GPU hybrid path
} else {
    // not a Qualcomm Snapdragon — register the llama runtime instead
}
```

| Flag | Meaning |
| --- | --- |
| `hexagonArch` | Detected Hexagon generation: `V73`, `V75`, `V77`, `V79`, `V81`, `UNKNOWN` |
| `npuSupported` | `true` when the bundled GenieX AAR ships QNN HTP kernels for this arch (v79/v81) |
| `hexagonArch.isHexagon` | `true` when a Qualcomm Hexagon DSP was detected at all |

## Supported devices / NPUs

| Hexagon arch | SoC | Example devices | NPU kernels in AAR |
| --- | --- | --- | --- |
| v79 | Snapdragon 8 Elite (SM8750) | Galaxy S25, OnePlus 13, Xiaomi 15 | ✅ (`libQnnHtpV79*`) |
| v81 | Snapdragon 8 Elite Gen 5 (SM8850) | upcoming 2026 flagships | ✅ (`libQnnHtpV81*`) |
| v75 | Snapdragon 8 Gen 2 (SM8550) | Galaxy S23, OnePlus 11 | ⚠️ CPU/GPU hybrid only |
| v77 | Snapdragon 8 Gen 3 (SM8650) | Galaxy S24, OnePlus 12 | ⚠️ CPU/GPU hybrid only |
| v73 | Snapdragon 8 Gen 1 (SM8450) | Galaxy S22, Xiaomi 12 | ⚠️ CPU/GPU hybrid only |

Arch detection is **conservative** — it maps known `SMxxxx` SoC models (from `Build.SOC_MODEL`,
`ro.soc.model`) and Qualcomm platform codenames (`kalama`, `pineapple`, `sun`, `dibda`, …).
Anything else reports `UNKNOWN` and the runtime warns that NPU kernels are unavailable.

On non-NPU-capable devices GenieX's bundled llama.cpp runtime still runs GGUF models on
CPU / Adreno GPU (`hybrid`) — slower than the NPU, but functional.

## Model metadata knobs

`runtime-qnn` reads a few optional keys from `Model.metadata`:

| Key | Values | Default |
| --- | --- | --- |
| `geniexRuntime` | `"llama_cpp"` (GGUF) or `"qairt"` (QNN bundle) | `"qairt"` for `QNN`, else `"llama_cpp"` |
| `computeUnit` | `"npu"`, `"hybrid"`, `"gpu"`, `"cpu"` | `"npu"` for QNN, `"hybrid"` for GGUF |
| `mmprojPath` | absolute path to the `.mmproj` vision encoder | none (text-only) |

```kotlin
val model = Model.remote(
    id = "smollm2-135m",
    url = "…/SmolLM2-135M-Instruct-Q4_K_M.gguf",
    format = ModelFormat.GGUF,
    metadata = mapOf(
        "computeUnit" to "npu",           // pin the Hexagon NPU (or "hybrid" for HTP+CPU)
        "mmprojPath" to "/data/…/mmproj-smolvlm.gguf",  // vision-capable model
    ),
)
```

QNN pre-compiled bundles from [Qualcomm AI Hub](https://aihub.qualcomm.com/models/) are
downloaded as an archive; point `Model.remote(…, format = ModelFormat.QNN)` at the bundle's
model path (or extract it and use `Model.local(path, format = ModelFormat.QNN)`).

## Notes & limitations

- **Embeddings / tokenize** are not exposed by the GenieX SDK — `sdk.embeddings()` throws
  `UnsupportedOperationException` for this runtime.
- **Vision (VLM)**: attach images via `sdk.stream(prompt, images = listOf(PromptAttachment(bytes)))`
  — they are materialized to temp files for GenieX and cleaned up after generation.
- **Licensing**: GenieX is BSD-3-Clause with Qualcomm's Terms of Use (see the
  [GenieX repo](https://github.com/qualcomm/GenieX)). It is a runtime dependency, not bundled
  into `runtime-qnn`'s AAR.

## Building from source

```sh
./gradlew :runtime-qnn:assembleDebug
./gradlew :runtime-qnn:testDebugUnitTest
```

No NDK / CMake is required — the GenieX AAR ships its own `arm64-v8a` natives.
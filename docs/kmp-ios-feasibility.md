# KMP / iOS feasibility spike — decision doc

**Status:** Spike complete (research spike — not a port)
**Date:** 2026-08
**Issue:** [sgaikar1/EdgeDroidSdk#15](https://github.com/sgaikar1/EdgeDroidSdk/issues/15)
**Branch:** `ao/edgedroidsdk-5/kmp-ios-spike`

## TL;DR

Feasibility is **proven**. `edgedroid-common` and `edgedroid-core` now build as Kotlin
Multiplatform (KMP) modules and **compile and link for `iosArm64`** (and
`iosSimulatorArm64`), while the existing Android app still assembles untouched. The
Android-only surfaces have been enumerated and isolated behind expect/actual + a small
`PlatformServices` interface. A stub iOS runtime implements the full `stream()`/`generate()`
SPI path, and a Metal probe confirms the Kotlin/Native Metal platform bindings resolve.
A full port is estimated at **3–5 engineer-weeks** (see [Timeline](#timeline-estimate)).

## What the spike proved

| Claim | Evidence |
| --- | --- |
| `common` compiles for iOS | `./gradlew :edgedroid-common:compileKotlinIosArm64` ✅ |
| `core` (SPI) compiles for iOS | `./gradlew :edgedroid-core:compileKotlinIosArm64` ✅ |
| iOS framework links (Xcode-consumable) | `./gradlew :edgedroid-core:linkDebugFrameworkIosArm64` ✅ (`EdgeDroidCore.framework`) |
| `stream()`/`generate()` path compiles on iOS | `IosStubEngine` + `StubMetalRuntime` in `iosMain` ✅ |
| Metal bindings resolve | `MetalProbe` (imports `platform.Metal.MTLCreateSystemDefaultDevice`) ✅ |
| Android side still green | `./gradlew :sample-app:assembleDebug` ✅ |
| Android unit tests still green | `:edgedroid-common:testDebugUnitTest`, `:edgedroid-core:testDebugUnitTest` ✅ |

The Kotlin/Native toolchain (2.1.21, macos-aarch64) downloads on first run into `~/.konan`
(~1.9 GB) — a one-time cost per developer/CI machine.

## Module graph

### After this spike

```
                       ┌─────────────────────────────────────────────┐
                       │             edgedroid-common  (KMP)          │
                       │  commonMain: GenerationOptions, LogProvider, │
                       │  ModelFormat, SdkResult, Token               │
                       └──────────────┬───────────────────────────────┘
                                      │ api
                       ┌──────────────▼───────────────────────────────┐
                       │             edgedroid-core  (KMP)             │
                       │  commonMain: Runtime, RuntimePlugin, LlmEngine,│
                       │   PromptProcessor, ChatSession, Model,        │
                       │   ModelDownloadState, DeviceCapabilities,     │
                       │   GpuConfig, RuntimeConfig, Compatibility*,   │
                       │   Downloader, ModelProvider, Capability       │
                       │   + PlatformServices interface +              │
                       │     internal expect availableProcessors()     │
                       │  androidMain: Cache, ModelStorage,            │
                       │     actual availableProcessors()              │
                       │  iosMain: StubMetalRuntime, StubMetalPlugin,  │
                       │     IosStubEngine, IosPlatformServices,       │
                       │     MetalProbe                                │
                       └──────┬──────────────────────┬─────────────────┘
                              │ android variant     │ iosArm64 / iosSimulatorArm64
              ┌───────────────▼───────────┐   ┌─────▼──────────────────┐
              │  Android-only modules     │   │  EdgeDroidCore.framework│
              │  edgedroid-api            │   │  (spike proof; Xcode    │
              │  edgedroid-storage        │   │  consumption later)     │
              │  edgedroid-download       │   └─────────────────────────┘
              │  runtime-llama (JNI/Vulkan)│
              │  runtime-onnx (JNI/ORT)   │
              │  sample-app               │
              └───────────────────────────┘
```

Targets declared: `androidTarget()`, `iosArm64()`, `iosSimulatorArm64()`.
`iosX64()` (Intel simulator) was deliberately omitted from the spike to keep the build lean;
add it when an Intel-simulator CI lane appears.

## What moves to KMP vs what stays Android

### Moves (this spike)

- **`edgedroid-common`** → KMP. All five files are pure Kotlin and needed no changes; they
  compile as-is in `commonMain`.
- **`edgedroid-core`** → KMP. The SPI (Runtime / RuntimePlugin / LlmEngine / Model /
  PromptProcessor / session / compatibility / downloader / provider contracts) is platform
  agnostic by design and compiles in `commonMain` after two small seams:

### Stays Android (not moved, and why)

| Module | Why it stays |
| --- | --- |
| `edgedroid-api` | `EdgeDroid.Builder(context)` is a `Context`-driven facade; holds `AndroidDeviceCapabilities`, `DefaultCache`, `SdkEngine`, download wiring. The app-facing API surface on iOS will be a new thin Swift/KMP facade, not this class. |
| `edgedroid-storage` | `StoragePaths` is `Context.filesDir`-based; `ModelStorageImpl` is `java.io.File`-backed metadata store. |
| `edgedroid-download` | Foreground-service download host (`DownloadService`, notifications, `ContextCompat.startForegroundService`). |
| `runtime-llama` | JNI + bundled llama.cpp, Vulkan backend, NDK build. |
| `runtime-onnx` | JNI + onnxruntime-android AAR. |
| `sample-app` | Android app. |

## Android-only API surface — enumeration

Every Android-ism found in the SDK modules, grouped by the seam that isolates it:

| Android API | Where | Seam / disposition |
| --- | --- | --- |
| `android.content.Context` (app-private `filesDir`, services, package manager) | `edgedroid-api` `EdgeDroid.Builder` / `AndroidDeviceCapabilities`; `edgedroid-storage` `StoragePaths` | **Stays Android.** iOS counterpart = `IosPlatformServices.appDataDirectory` (NSFileManager Documents dir). |
| `ActivityManager` / `PackageManager` / `Environment` / `Build.SUPPORTED_ABIS` (RAM, storage, ABIs, Vulkan feature) | `edgedroid-api` `AndroidDeviceCapabilities` | **Stays Android** behind the `DeviceCapabilities` data class (commonMain). iOS fills the same struct via `NSProcessInfo`/`sysctl`. |
| Foreground-service downloads (`Service`, `NotificationCompat`, `ServiceCompat.startForeground`, `ContextCompat.startForegroundService`, `PendingIntent`) | `edgedroid-download` `DownloadManager`/`DownloadService` | **Stays Android** behind the `Downloader` interface (commonMain). iOS will use `URLSession` background transfers behind the same interface. |
| `java.io.File` paths in core contracts | `edgedroid-core` `Cache`, `ModelStorage` | **Moved to `androidMain`** this spike. iOS gets file-path backings via `IosPlatformServices` (Documents dir). |
| `java.lang.Runtime.getRuntime().availableProcessors()` | `edgedroid-core` `RuntimeConfig` thread defaults | **expect/actual** this spike: `internal expect fun availableProcessors()` → Android actual = `Runtime.availableProcessors()`, iOS actual = `NSProcessInfo.activeProcessorCount`. |
| `@JvmStatic` | `edgedroid-core` `GpuConfig` | Not available in common code; annotation dropped (Kotlin callers unaffected; Java callers use `GpuConfig.Companion.layers(...)`). |

**PowerManager** was not found anywhere in the SDK (the search in the issue listed it as a
suspected Android-ism); only the API/UI layer uses `Context`/`ActivityManager`. If a future
feature adds "keep screen on while generating", it belongs behind a `PlatformServices` seam
(`PowerManager` → iOS `UIApplication.shared.isIdleTimerDisabled`).

## The seams introduced

1. **`internal expect fun availableProcessors(): Int`** in `commonMain` — the smallest
   expect/actual that unblocks the SPI on iOS.
2. **`PlatformServices` interface** in `commonMain` (`appDataDirectory`, `cpuCoreCount`) —
   the public seam for everything else. Android implementation is assembled in the
   app-facing modules (`Context.filesDir`); a real Foundation-backed
   `IosPlatformServices` is included in `iosMain` as the reference implementation.
3. **`Cache` / `ModelStorage` relocated to `androidMain`** — file-path-typed contracts are
   Android-only by construction until the port needs them on iOS.

## iOS stub runtime (the `stream()`/`generate()` proof)

`edgedroid-core/src/iosMain/`:

- `StubMetalRuntime : Runtime` — `generate(handle, PromptParts, options): Flow<Token>`
  emits a canned echo; `tokenize`/`embeddings`/`stop`/lifecycle stubbed.
- `StubMetalPlugin : RuntimePlugin` — registration contract identical to a future
  `MetalRuntimePlugin` (`id = "metal-stub"`, `arm64-apple-ios` ABI).
- `IosStubEngine : LlmEngine` — implements `stream()`/`generate()` on top of the stub
  runtime; mirrors the public entry points `EdgeDroid` exposes on Android.
- `MetalProbe` — `MTLCreateSystemDefaultDevice() != null`, proving the Kotlin/Native Metal
  platform bindings compile for iosArm64 (MetalFX/MPSGraph would use the same cinterop path).
- `IosPlatformServices` — real Foundation-backed `PlatformServices`.

A production Metal runtime is a separate module (e.g. `runtime-metal`) implementing the same
`RuntimePlugin` contract with a cinterop bridge to Metal Performance Shaders / MPSGraph; no
core changes are expected.

## Risks

| Risk | Severity | Mitigation |
| --- | --- | --- |
| **Metal inference gap is the real work.** Kotlin/Native has no llama.cpp; a Metal backend (MPSGraph/MPSCNN) or a llama.cpp-for-iOS C bridge must be built/verified. | High | Spike proves only the SPI/compiler side. First port task = smallest Metal backend that runs a tiny GGUF. |
| `Cache`/`ModelStorage` android-only means iOS core lacks file contracts today | Medium | `IosPlatformServices` template exists; port `ModelStorage` to NSFileManager when the first iOS runtime needs model files. |
| Serialization of `Model` (kotlinx) is fine, but `@Serializable` metadata/`Json` default config differences on native need re-verification in the real port | Low | Covered by `commonTest` when port tests are written. |
| AGP + KMP androidTarget publishing nuance: `publishLibraryVariants("release")` and the vanniktech `KotlinMultiplatform` publication now differ from the old Android-only publications | Low | Release pipeline (Maven Central publish) must be exercised once before shipping; no secrets/CI touched by the spike. |
| Kotlin/Native toolchain download (~1.9 GB) and macOS-only Apple targets | Low | CI for iOS must run on macOS runners; Android CI unaffected (Apple targets are skipped/lazy on non-mac hosts). |
| `runtime-llama`/`runtime-onnx` remain Android JNI modules — no iOS inference until replaced | High | Sequence the port: core+common first (done), then a Metal runtime module, then iOS storage/download, then the iOS facade. |
| Java-caller surface change: `@JvmStatic` dropped from `GpuConfig.layers` | Low | Kotlin API unchanged; document if any Java consumers exist. |

## Timeline estimate

Assumes one engineer, macOS host, no prior KMP/iOS experience assumed beyond this spike.

| Phase | Scope | Estimate |
| --- | --- | --- |
| **0 (this spike)** | KMP skeleton, seams, stub runtime, decision doc | ✅ done |
| 1 | Port `ModelStorage`/`Cache` to iOS (`IosPlatformServices`), common tests for `core` | 2–3 days |
| 2 | Minimal Metal runtime: load a tiny GGUF via Metal/MPSGraph or llama.cpp-for-iOS C bridge; implement `Runtime`/`RuntimePlugin` | 1.5–2.5 weeks |
| 3 | iOS download path (URLSession background transfers behind `Downloader`), model registry | 3–5 days |
| 4 | iOS app facade (`EdgeDroid`-equivalent Swift-friendly API), framework distribution (SPM/CocoaPods) | 3–5 days |
| 5 | VLM/embeddings parity, perf validation on device | 1–2 weeks |

**Total: 3–5 engineer-weeks** to a feature-parity iOS SDK (stream/generate + downloads),
with Phase 2 being the dominant risk and the first thing to de-risk.

## Files changed by the spike

- `gradle/libs.versions.toml`, `build.gradle.kts` — KMP plugin + `kotlinx-coroutines-core`.
- `edgedroid-common/` → KMP (`commonMain` sources unchanged, `androidMain` manifest).
- `edgedroid-core/` → KMP; `Cache.kt`/`ModelStorage.kt` → `androidMain`; new
  `Platform.kt` (expect), `Platform.android.kt`/`Platform.ios.kt` (actuals); new
  `iosMain` stub runtime + engine + Metal probe; `GpuConfigTest` → `androidUnitTest`.
- `docs/kmp-ios-feasibility.md` (this doc).
- `README.md` — future-scope row updated.
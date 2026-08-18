package com.sgaikar1.edgedroid.core

/**
 * Small platform-services seam that isolates the handful of OS services the SPI needs from the
 * platform that provides them. This is the "expect/actual or a small PlatformServices interface"
 * seam from the KMP/iOS feasibility spike:
 *
 * - **appDataDirectory** — where model/download/cache files live. Android backs it with
 *   `Context.filesDir` (`edgedroid-storage`/`StoragePaths`); iOS backs it with the app's
 *   Documents directory (`IosPlatformServices`).
 * - **cpuCoreCount** — used for sane default thread counts. Android reads `Runtime.availableProcessors()`;
 *   iOS reads `NSProcessInfo.activeProcessorCount`.
 *
 * The Android implementation is assembled in the app-facing modules (`edgedroid-api` builder);
 * the iOS reference implementation lives in `iosMain`.
 */
interface PlatformServices {
    /** App-private base directory for model/download/cache/temp storage. */
    val appDataDirectory: String

    /** Number of logical CPU cores. */
    val cpuCoreCount: Int
}

/**
 * Internal expect/actual used by [ThreadingConfig] defaults so `commonMain` never touches
 * `java.lang.Runtime`. Actuals: Android → `Runtime.availableProcessors()`, iOS →
 * `NSProcessInfo.activeProcessorCount`.
 */
internal expect fun availableProcessors(): Int
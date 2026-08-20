package com.sgaikar1.edgedroid.core

/**
 * Android actual of the platform seam. The app-facing modules wire the filesystem half
 * (`appDataDirectory` from `Context.filesDir`, see `edgedroid-storage` / `StoragePaths`);
 * the CPU count is a pure JVM call.
 */
internal actual fun availableProcessors(): Int =
    java.lang.Runtime.getRuntime().availableProcessors()
package com.sgaikar1.edgedroid.core

/**
 * JVM static home for [GpuConfig.layers] so Java callers keep a static entry point now that
 * the sealed class lives in `commonMain` (where `@JvmStatic` is unavailable). This follows the
 * androidx `*Compat` convention (`NotificationCompat`, `ServiceCompat`, …).
 *
 * Migration note: Java previously wrote `GpuConfig.layers(int)` — that exact static method
 * cannot be emitted from a common class, so Java callers should use `GpuConfigCompat.layers(int)`.
 * Kotlin callers are unaffected (`GpuConfig.layers(n)` still resolves to the companion function).
 */
object GpuConfigCompat {
    @JvmStatic
    fun layers(n: Int): GpuConfig = GpuConfig.Layers(n)
}
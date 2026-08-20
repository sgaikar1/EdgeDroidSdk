package com.sgaikar1.edgedroid.core

import com.sgaikar1.edgedroid.common.ModelFormat

/**
 * SPI registration of the iOS stub runtime — proves a runtime can be dropped onto iosArm64
 * without touching the core. The real port will ship `MetalRuntimePlugin` with the same shape,
 * with `supportedAbis = setOf("arm64-apple-ios")` and `Capability.STREAMING` (+ VISION when the
 * Metal backend can run a VLM).
 */
class StubMetalPlugin : RuntimePlugin {
    override val id: String = "metal-stub"
    override val version: String = "0.1.0-spike"
    override val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.GGUF)
    override val capabilities: Set<Capability> = setOf(Capability.STREAMING)
    override val supportedAbis: Set<String> = setOf("arm64-apple-ios")

    override suspend fun create(config: RuntimeConfig): Runtime = StubMetalRuntime()
}
package com.sgaikar1.edgedroid.runtime.onnx

import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.Capability
import com.sgaikar1.edgedroid.core.Runtime
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimePlugin

/**
 * ONNX Runtime (Microsoft onnxruntime-android) runtime: embeddings today, LLM generation and
 * vision as they are wired in. Registered as a plain [RuntimePlugin] — same SPI as llama.cpp.
 */
class OnnxPlugin : RuntimePlugin {

    override val id: String = "onnx"
    override val version: String = "0.1.0"
    override val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.ONNX)
    override val capabilities: Set<Capability> = setOf(Capability.EMBEDDINGS, Capability.STREAMING, Capability.VISION)
    override val supportedAbis: Set<String> = setOf("arm64-v8a", "x86_64", "armeabi-v7a")

    override suspend fun create(config: RuntimeConfig): Runtime = OnnxRuntime(config)
}

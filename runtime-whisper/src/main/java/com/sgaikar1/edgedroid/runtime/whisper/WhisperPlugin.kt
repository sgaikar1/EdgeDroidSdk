package com.sgaikar1.edgedroid.runtime.whisper

import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.Capability
import com.sgaikar1.edgedroid.core.Runtime
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimePlugin

/**
 * The whisper.cpp runtime, registered as a plain [RuntimePlugin]. The SDK core sees exactly
 * what it would see for llama, ONNX or any future runtime — nothing more.
 */
class WhisperPlugin : RuntimePlugin {

    override val id: String = "whisper"
    override val version: String = BuildConfig.SDK_VERSION

    // whisper.cpp loads both the classic GGML .bin models and GGUF whisper models.
    override val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.GGUF, ModelFormat.CUSTOM)
    override val capabilities: Set<Capability> = setOf(Capability.AUDIO, Capability.STREAMING)
    override val supportedAbis: Set<String> = setOf("arm64-v8a", "x86_64")

    override suspend fun create(config: RuntimeConfig): Runtime = WhisperRuntime(config)
}
package com.sgaikar1.edgedroid.runtime.llama

import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.Capability
import com.sgaikar1.edgedroid.core.Runtime
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimePlugin

/**
 * The llama.cpp runtime, registered as a plain [RuntimePlugin]. The SDK core sees exactly what
 * it would see for ExecuTorch, LiteRT or any future runtime — nothing more.
 */
class LlamaPlugin : RuntimePlugin {

    override val id: String = "llama"
    override val version: String = "0.1.0"
    override val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.GGUF)
    override val capabilities: Set<Capability> = setOf(Capability.STREAMING)

    override suspend fun create(config: RuntimeConfig): Runtime = LlamaRuntime(config)
}

package com.sgaikar1.edgedroid.runtime.executorch

import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.Capability
import com.sgaikar1.edgedroid.core.Runtime
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimePlugin

/**
 * The ExecuTorch runtime (PyTorch's mobile inference engine), registered as a plain
 * [RuntimePlugin]. Loads `.pte` module files and streams tokens through ExecuTorch's
 * `LlmModule` extension. Vision is supported for exports compiled with a multimodal
 * (`text-vision`) runner — declare it per model via metadata `modelType`/extras.
 *
 * The SDK core sees exactly what it sees for llama.cpp or ONNX — nothing more.
 */
class ExecuTorchPlugin : RuntimePlugin {

    override val id: String = "executorch"
    override val version: String = BuildConfig.SDK_VERSION
    override val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.PTE)
    override val capabilities: Set<Capability> = setOf(Capability.STREAMING, Capability.VISION)

    // Mirrors the ABIs shipped by org.pytorch:executorch-android (and the module's abiFilters),
    // so AUTO selection and the compatibility checker agree on what this device can run.
    override val supportedAbis: Set<String> = setOf("arm64-v8a", "x86_64")

    override suspend fun create(config: RuntimeConfig): Runtime = ExecuTorchRuntime(config)
}
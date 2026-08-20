package com.sgaikar1.edgedroid.runtime.tts

import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.Capability
import com.sgaikar1.edgedroid.core.Runtime
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimePlugin
import com.sgaikar1.edgedroid.runtime.onnx.OnnxPlugin

/**
 * Kokoro-82M text-to-speech runtime, packaged as a [RuntimePlugin] exactly like the other
 * runtimes. Registers [Capability.AUDIO] with no core changes; the SDK dispatches on capability,
 * never on runtime identity. Reuses the onnxruntime-android wiring/ABI list from [OnnxPlugin].
 */
class KokoroTtsPlugin : RuntimePlugin {

    override val id: String = "kokoro-tts"
    override val version: String = "0.1.0"
    override val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.ONNX)
    override val capabilities: Set<Capability> = setOf(Capability.AUDIO)
    override val supportedAbis: Set<String> = OnnxPlugin().supportedAbis

    override suspend fun create(config: RuntimeConfig): Runtime = KokoroTtsRuntime(config)
}

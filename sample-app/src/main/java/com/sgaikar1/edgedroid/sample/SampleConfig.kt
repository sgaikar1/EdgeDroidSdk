package com.sgaikar1.edgedroid.sample

import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.DeviceCapabilities
import com.sgaikar1.edgedroid.core.GpuConfig

enum class SampleRuntime { LLAMA, ONNX }

data class SampleModel(
    val id: String,
    val label: String,
    val runtime: SampleRuntime,
    val format: ModelFormat,
    val url: String,
    val sizeBytes: Long?,
    val metadata: Map<String, String>,
    val chatCapable: Boolean,
    val embeddingCapable: Boolean,
    val visionCapable: Boolean = false,
    val mmprojUrl: String? = null,
) {
    /**
     * True if this model can realistically run on [caps]: the model file (plus download
     * headroom) fits the free storage, and a runtime for its format is registered.
     */
    fun fitsDevice(caps: DeviceCapabilities): Boolean {
        if (caps.freeStorageBytes <= 0L) return true
        val size = sizeBytes ?: return true
        return size + SampleConfig.STORAGE_HEADROOM_BYTES <= caps.freeStorageBytes
    }
}

object SampleModels {
    val ALL: List<SampleModel> = listOf(
        SampleModel(
            id = "smollm2-135m-instruct",
            label = "SmolLM2 135M Instruct (Q4_K_M)",
            runtime = SampleRuntime.LLAMA,
            format = ModelFormat.GGUF,
            url = "https://huggingface.co/unsloth/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf",
            sizeBytes = 105_454_144L,
            metadata = mapOf("template" to "chatml"),
            chatCapable = true,
            embeddingCapable = false,
        ),
        SampleModel(
            id = "all-minilm-l6-v2",
            label = "all-MiniLM-L6-v2 (int8)",
            runtime = SampleRuntime.ONNX,
            format = ModelFormat.ONNX,
            url = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx",
            sizeBytes = 22_972_370L,
            metadata = mapOf("template" to "raw"),
            chatCapable = false,
            embeddingCapable = true,
        ),
        SampleModel(
            id = "SmolVLM-256M-Instruct",
            label = "SmolVLM-256M Instruct (Q8_0, vision)",
            runtime = SampleRuntime.LLAMA,
            format = ModelFormat.GGUF,
            url = "https://huggingface.co/ggml-org/SmolVLM-256M-Instruct-GGUF/resolve/main/SmolVLM-256M-Instruct-Q8_0.gguf",
            sizeBytes = 175_054_528L,
            mmprojUrl = "https://huggingface.co/ggml-org/SmolVLM-256M-Instruct-GGUF/resolve/main/mmproj-SmolVLM-256M-Instruct-Q8_0.gguf",
            metadata = mapOf("template" to "chatml"),
            chatCapable = true,
            embeddingCapable = false,
            visionCapable = true,
        ),
    )

    fun byId(id: String): SampleModel = ALL.first { it.id == id }

    fun forRuntime(runtime: SampleRuntime): List<SampleModel> = ALL.filter { it.runtime == runtime }

    /** Build a [SampleModel] from an HF-browser selection. */
    fun fromHf(sel: HfModelSelection): SampleModel = SampleModel(
        id = sel.repoId,
        label = sel.label,
        runtime = if (sel.format.isOnnx) SampleRuntime.ONNX else SampleRuntime.LLAMA,
        format = sel.format,
        url = HfHubClient.resolveUrl(sel.repoId, sel.fileName),
        sizeBytes = sel.sizeBytes,
        metadata = mapOf("template" to if (sel.format.isOnnx) "raw" else "chatml"),
        chatCapable = !sel.format.isOnnx,
        embeddingCapable = sel.format.isOnnx,
    )

    /** Kokoro-82M TTS model used for the text->speech "Speak" action. */
    val KOKORO_TTS: SampleModel = SampleModel(
        id = "kokoro-82m-tts",
        label = "Kokoro-82M TTS (q8f16)",
        runtime = SampleRuntime.ONNX,
        format = ModelFormat.ONNX,
        url = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model_q8f16.onnx",
        sizeBytes = 86_033_585L,
        metadata = mapOf("template" to "raw"),
        chatCapable = false,
        embeddingCapable = false,
        visionCapable = false,
    )
}

data class SampleConfig(
    val runtime: SampleRuntime = SampleRuntime.LLAMA,
    val modelId: String = "smollm2-135m-instruct",
    val customModel: HfModelSelection? = null,
    val threads: Int = 8,
    val contextSize: Int = 2048,
    val gpu: GpuConfig = GpuConfig.Auto,
    val executionProvider: String? = "NNAPI",
    val maxRetries: Int = 3,
    val downloadTimeoutSeconds: Long = 60,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val systemPrompt: String = "",
) {
    /** The effective model: a preset or the HF-browser pick. */
    val model: SampleModel
        get() = customModel?.let { SampleModels.fromHf(it) } ?: SampleModels.byId(modelId)

    fun withRuntime(runtime: SampleRuntime): SampleConfig {
        if (runtime == this.runtime) return this
        val first = SampleModels.forRuntime(runtime).first()
        return copy(runtime = runtime, modelId = first.id, customModel = null)
    }

    companion object {
        /** Extra free storage kept free beyond the model file (mirrors the SDK checker). */
        const val STORAGE_HEADROOM_BYTES = 256L * 1024 * 1024

        /**
         * Device-aware defaults. Called on first launch (no persisted config) and by the
         * "Reset to device defaults" button; every value remains user-tweakable afterwards.
         */
        fun recommended(caps: DeviceCapabilities): SampleConfig {
            val threads = caps.cpuCores.coerceIn(2, 8)
            val contextSize = when {
                caps.totalRamBytes >= 8L * 1024 * 1024 * 1024 -> 4096
                caps.totalRamBytes >= 4L * 1024 * 1024 * 1024 -> 2048
                else -> 1024
            }
            val gpu = if (caps.vulkanSupported) GpuConfig.Auto else GpuConfig.Cpu
            val firstModel = SampleModels.forRuntime(SampleRuntime.LLAMA).first()
            return SampleConfig(
                threads = threads,
                contextSize = contextSize,
                gpu = gpu,
                modelId = firstModel.id,
            )
        }
    }
}

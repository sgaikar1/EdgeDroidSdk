package com.sgaikar1.edgedroid.sample

import com.sgaikar1.edgedroid.common.ModelFormat
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
)

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
}

data class SampleConfig(
    val runtime: SampleRuntime = SampleRuntime.LLAMA,
    val modelId: String = "smollm2-135m-instruct",
    val customModel: HfModelSelection? = null,
    val threads: Int = 4,
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
}

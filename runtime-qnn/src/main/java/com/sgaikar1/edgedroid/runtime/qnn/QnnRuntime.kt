package com.sgaikar1.edgedroid.runtime.qnn

import android.content.Context
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmCreateInput
import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.common.Token
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.ModelHandle
import com.sgaikar1.edgedroid.core.PromptProcessor
import com.sgaikar1.edgedroid.core.Runtime
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Concrete [Runtime] over the Qualcomm GenieX Android SDK — QNN (AI Engine Direct) on the
 * Hexagon NPU, with GenieX's bundled llama.cpp runtime as the CPU/GPU/hybrid path.
 *
 * The runtime is single-model: [loadModel] replaces any previous session. Model selection knobs
 * are read from [Model.metadata]:
 *
 * - `geniexRuntime` — `"llama_cpp"` (GGUF, default) or `"qairt"` (QNN pre-compiled bundle).
 *   Defaults to `"qairt"` for [ModelFormat.QNN] models, `"llama_cpp"` for GGUF.
 * - `computeUnit` — `"npu"`, `"hybrid"`, `"gpu"` or `"cpu"`. Defaults to `"npu"` for QNN
 *   bundles and `"hybrid"` for GGUF (per-tensor HTP+CPU — the Snapdragon fast path).
 * - `mmprojPath` — path to the vision encoder (`.mmproj`) for image→text (VLM) models.
 *
 * The [ModelHandle] returned by [loadModel] is an opaque token; the native session lives inside
 * this runtime, mirroring the other EdgeDroid runtimes.
 */
internal class QnnRuntime(
    private val config: RuntimeConfig,
    private val appContext: Context,
) : Runtime {

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Uninitialized)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    /** Hexagon arch re-detected from the device at runtime construction. */
    private val arch: HexagonArch = HexagonDetector.detect(appContext)

    private var initialized = false
    private var session: GenieXModelSession? = null
    private var isVlm = false
    private val tokenCounter = AtomicLong(0)

    override suspend fun initialize() {
        if (initialized) return
        config.log.log(LogProvider.Level.INFO, TAG, "Hexagon arch: ${arch.name} (NPU kernels: ${arch.isNpuCapable})")
        when {
            arch == HexagonArch.UNKNOWN -> config.log.log(
                LogProvider.Level.WARN, TAG,
                "No Qualcomm Hexagon DSP detected — GenieX will run on CPU/GPU (llama_cpp); NPU kernels unavailable",
            )
            !arch.isNpuCapable -> config.log.log(
                LogProvider.Level.WARN, TAG,
                "Hexagon ${arch.name} detected, but the bundled GenieX AAR ships HTP kernels only for v79/v81 — " +
                    "NPU offload unavailable; using CPU/GPU (llama_cpp) hybrid path",
            )
        }
        GenieXSession.ensureInitialized(appContext, config.log)
        initialized = true
        _state.value = RuntimeState.Initialized
        config.log.log(LogProvider.Level.INFO, TAG, "QNN/GenieX runtime initialized")
    }

    override suspend fun loadModel(model: Model, options: RuntimeConfig): ModelHandle {
        val path = model.localPath ?: throw IllegalArgumentException(
            "Model '${model.id}' has no local path — ensure it is downloaded before load",
        )
        GenieXSession.ensureInitialized(appContext, config.log)
        return withContext(Dispatchers.Default) {
            val runtimeId = model.metadata["geniexRuntime"]
                ?: if (model.format == ModelFormat.QNN) RUNTIME_QAIRT else RUNTIME_LLAMA_CPP
            val computeUnit = model.metadata["computeUnit"]
                ?: if (model.format == ModelFormat.QNN) ComputeUnitValue.NPU.value else ComputeUnitValue.HYBRID.value
            val mmprojPath = model.metadata["mmprojPath"]

            val modelConfig = ModelConfig(
                nCtx = options.memory.contextSize,
                nThreads = options.threading.threads,
                nThreadsBatch = options.threading.batchThreads,
                nBatch = options.memory.batchSize,
                nGpuLayers = options.memory.gpu.toNGpuLayers(if (arch.isNpuCapable) 1 else 0),
            )

            val created: GenieXModelSession = if (mmprojPath.isNullOrBlank()) {
                val wrapper = LlmWrapper.builder()
                    .llmCreateInput(
                        LlmCreateInput(
                            model_path = path,
                            config = modelConfig,
                            runtime_id = runtimeId,
                            compute_unit = computeUnit,
                        ),
                    )
                    .build()
                    .getOrElse { throw IllegalStateException("GenieX LLM session creation failed: ${it.message}", it) }
                GenieXLlm(wrapper)
            } else {
                val wrapper = VlmWrapper.builder()
                    .vlmCreateInput(
                        VlmCreateInput(
                            model_path = path,
                            mmproj_path = mmprojPath,
                            config = modelConfig,
                            runtime_id = runtimeId,
                            compute_unit = computeUnit,
                        ),
                    )
                    .build()
                    .getOrElse { throw IllegalStateException("GenieX VLM session creation failed: ${it.message}", it) }
                GenieXVlm(wrapper)
            }

            session?.destroy()
            session = created
            isVlm = !mmprojPath.isNullOrBlank()
            config.log.log(
                LogProvider.Level.INFO, TAG,
                "GenieX session created (runtime=$runtimeId, compute=$computeUnit, vlm=$isVlm, arch=${arch.name})",
            )
            _state.value = RuntimeState.ModelLoaded
            1L
        }
    }

    override suspend fun unload(handle: ModelHandle) {
        withContext(Dispatchers.Default) {
            session?.destroy()
            session = null
            isVlm = false
        }
        _state.value = RuntimeState.Initialized
    }

    override suspend fun generate(
        handle: ModelHandle,
        prompt: PromptProcessor.PromptParts,
        options: GenerationOptions,
    ): Flow<Token> = callbackFlow {
        val s = session ?: throw IllegalStateException("Model not loaded")
        val rendered = renderPrompt(prompt, isVlm)
        val imageFiles = withContext(Dispatchers.IO) { writeImageAttachments(prompt.attachments) }

        try {
            val geniexConfig = options.toGenieXConfig(imageFiles)
            var index = 0L
            s.generateStream(rendered, geniexConfig).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> {
                        trySend(Token(index = index, id = tokenCounter.getAndIncrement(), text = result.text))
                        index++
                    }
                    is LlmStreamResult.Completed -> close()
                    is LlmStreamResult.Error -> throw result.throwable
                }
            }
        } finally {
            imageFiles.forEach { it.delete() }
        }
        close()
    }

    override suspend fun tokenize(handle: ModelHandle, text: String): List<Int> {
        throw UnsupportedOperationException("The GenieX SDK does not expose tokenization")
    }

    override suspend fun embeddings(handle: ModelHandle, text: String): FloatArray {
        throw UnsupportedOperationException("The QNN/GenieX runtime does not expose embeddings")
    }

    override suspend fun stop(handle: ModelHandle) {
        session?.stop()
    }

    /** Insert the `<image>` marker for VLM generation when the prompt lacks it (like llama.cpp). */
    private fun renderPrompt(prompt: PromptProcessor.PromptParts, vlm: Boolean): String {
        val full = prompt.render()
        if (vlm && prompt.attachments.isNotEmpty() && !full.contains("<image>")) {
            return prompt.prefix + "<image>\n" + prompt.body
        }
        return full
    }

    /** GenieX consumes images by path; materialize [PromptProcessor.PromptAttachment]s to temp files. */
    private fun writeImageAttachments(attachments: List<PromptProcessor.PromptAttachment>): List<File> {
        if (attachments.isEmpty()) return emptyList()
        val dir = File(appContext.cacheDir, "edgedroid_qnn").apply { mkdirs() }
        return attachments.mapIndexed { i, att ->
            val ext = att.mimeType.substringAfter('/', "jpg").ifBlank { "jpg" }
            File(dir, "image_${System.currentTimeMillis()}_$i.$ext").apply { writeBytes(att.bytes) }
        }
    }

    /** Maps EdgeDroid [GenerationOptions] onto GenieX [GenerationConfig] + [SamplerConfig]. */
    private fun GenerationOptions.toGenieXConfig(imageFiles: List<File>): GenerationConfig {
        val sampler = SamplerConfig().apply {
            temperature = this@toGenieXConfig.temperature
            topP = this@toGenieXConfig.topP
            topK = this@toGenieXConfig.topK
            minP = this@toGenieXConfig.minP
            repetitionPenalty = this@toGenieXConfig.repeatPenalty
            // GenieX: 0 = defer to the bundle default; EdgeDroid -1 = "random".
            seed = if (this@toGenieXConfig.seed > 0) this@toGenieXConfig.seed else 0
        }
        return GenerationConfig().apply {
            maxTokens = this@toGenieXConfig.maxTokens
            samplerConfig = sampler
            if (stopSequences.isNotEmpty()) {
                stopWords = stopSequences.toTypedArray()
                stopCount = stopSequences.size
            }
            if (imageFiles.isNotEmpty()) {
                imagePaths = imageFiles.map { it.absolutePath }.toTypedArray()
                imageCount = imageFiles.size
            }
        }
    }

    companion object {
        private const val TAG = "EdgeDroid.QnnRuntime"
        private const val RUNTIME_LLAMA_CPP = "llama_cpp"
        private const val RUNTIME_QAIRT = "qairt"
    }
}

/** Minimal seam over the GenieX LLM/VLM wrappers so [QnnRuntime] treats them uniformly. */
private sealed interface GenieXModelSession {
    suspend fun generateStream(prompt: String, config: GenerationConfig): Flow<LlmStreamResult>
    suspend fun stop()
    fun destroy()
}

private class GenieXLlm(private val wrapper: LlmWrapper) : GenieXModelSession {
    override suspend fun generateStream(prompt: String, config: GenerationConfig): Flow<LlmStreamResult> =
        wrapper.generateStreamFlow(prompt, config)

    override suspend fun stop() {
        wrapper.stopStream()
    }

    override fun destroy() {
        wrapper.destroy()
    }
}

private class GenieXVlm(private val wrapper: VlmWrapper) : GenieXModelSession {
    override suspend fun generateStream(prompt: String, config: GenerationConfig): Flow<LlmStreamResult> =
        wrapper.generateStreamFlow(prompt, config)

    override suspend fun stop() {
        wrapper.stopStream()
    }

    override fun destroy() {
        wrapper.destroy()
    }
}
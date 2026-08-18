package com.sgaikar1.edgedroid.runtime.llama

import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.GenerationStats
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.common.Token
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.ModelHandle
import com.sgaikar1.edgedroid.core.PromptProcessor
import com.sgaikar1.edgedroid.core.Runtime
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimeState
import com.sgaikar1.edgedroid.core.StreamMetricsTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Concrete [Runtime] over llama.cpp. Implements only the interface — the SDK never reaches
 * into llama specifics through it.
 */
internal class LlamaRuntime(private val config: RuntimeConfig) : Runtime {

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Uninitialized)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var initialized = false
    private var gpuDeviceCount = 0
    private val tokenCounter = AtomicLong(0)

    // Cumulative generation stats for this session (since load). Updated as a side effect of
    // generate() from llama.cpp's own llama_perf_* counters.
    private val stats = AtomicReference(GenerationStats())

    override fun stats(): GenerationStats = stats.get()

    override suspend fun initialize() {
        if (initialized) return
        withContext(Dispatchers.Default) {
            NativeLlama.nativeInit()
            gpuDeviceCount = NativeLlama.nativeGpuDeviceCount()
        }
        initialized = true
        _state.value = RuntimeState.Initialized
        config.log.log(
            LogProvider.Level.INFO, TAG,
            "llama backend initialized, GPU devices: $gpuDeviceCount",
        )
    }

    override suspend fun loadModel(model: Model, options: RuntimeConfig): ModelHandle {
        val path = model.localPath ?: throw IllegalArgumentException(
            "Model '${model.id}' has no local path — ensure it is downloaded before load",
        )
        val gpuConfig = options.memory.gpu
        var nGpuLayers = gpuConfig.toNGpuLayers(gpuDeviceCount)

        var handle = loadInternal(path, options, nGpuLayers)
        // Safety net: a device may report Vulkan but fail at load time. Retry on CPU.
        if (handle == 0L && nGpuLayers != 0) {
            config.log.log(
                LogProvider.Level.WARN, TAG,
                "GPU load failed (gpuLayers=$nGpuLayers); falling back to CPU",
            )
            nGpuLayers = 0
            handle = loadInternal(path, options, 0)
        }
        if (handle == 0L) throw RuntimeException("Failed to load model at $path")
        _state.value = RuntimeState.ModelLoaded
        val mmproj = model.metadata["mmprojPath"]
        if (!mmproj.isNullOrBlank()) {
            val ok = withContext(Dispatchers.Default) {
                NativeLlama.nativeLoadVisionModel(handle, mmproj, options.threading.threads)
            }
            if (!ok) throw RuntimeException("Failed to load mmproj vision model at $mmproj")
            config.log.log(LogProvider.Level.INFO, TAG, "Vision (mmproj) loaded from $mmproj")
        }
        return handle
    }

    private suspend fun loadInternal(path: String, options: RuntimeConfig, nGpuLayers: Int): ModelHandle =
        withContext(Dispatchers.Default) {
            NativeLlama.nativeLoadModel(
                path = path,
                nCtx = options.memory.contextSize,
                nThreads = options.threading.threads,
                nThreadsBatch = options.threading.batchThreads,
                nBatch = options.memory.batchSize,
                nGpuLayers = nGpuLayers,
                mmap = options.memory.mmap,
            )
        }

    override suspend fun unload(handle: ModelHandle) {
        if (handle != 0L) withContext(Dispatchers.Default) { NativeLlama.nativeUnload(handle) }
        _state.value = RuntimeState.Initialized
    }

    override suspend fun generate(
        handle: ModelHandle,
        prompt: PromptProcessor.PromptParts,
        options: GenerationOptions,
    ): Flow<Token> = callbackFlow {
        var index = 0L
        val metrics = StreamMetricsTracker()
        val callback = NativeLlama.TokenCallback { text ->
            trySend(
                Token(
                    index = index,
                    id = tokenCounter.getAndIncrement(),
                    text = text,
                    metrics = metrics.onToken(),
                ),
            )
            index++
        }
        // Native perf for this call: [t_p_eval_ms, t_eval_ms, n_p_eval, n_eval] from
        // llama_perf_context, diffed inside the JNI layer so each call is self-contained.
        val perf = withContext(Dispatchers.Default) {
            val image = prompt.attachments.firstOrNull()
            if (image != null) {
                // Vision path: the text must contain the <image> marker; insert it if absent.
                val rendered = if (prompt.render().contains("<image>")) {
                    prompt.render()
                } else {
                    prompt.prefix + "<image>\n" + prompt.body
                }
                NativeLlama.nativeGenerateVision(
                    handle = handle,
                    prompt = rendered,
                    imageBytes = image.bytes,
                    temperature = options.temperature,
                    topK = options.topK,
                    topP = options.topP,
                    minP = options.minP,
                    maxTokens = options.maxTokens,
                    repeatPenalty = options.repeatPenalty,
                    seed = options.seed,
                    callback = callback,
                )
            } else {
                // Cache the (constant) system-prefix KV once; only the per-call body is re-decoded.
                if (!NativeLlama.nativeSetPrefix(handle, prompt.prefix)) {
                    throw IllegalStateException("Failed to cache prompt prefix")
                }
                NativeLlama.nativeGenerate(
                    handle = handle,
                    body = prompt.body,
                    temperature = options.temperature,
                    topK = options.topK,
                    topP = options.topP,
                    minP = options.minP,
                    maxTokens = options.maxTokens,
                    repeatPenalty = options.repeatPenalty,
                    seed = options.seed,
                    callback = callback,
                )
            }
        }
        if (perf != null && perf.size >= 4) {
            stats.updateAndGet { previous ->
                previous + GenerationStats(
                    promptTokens = perf[2].toLong(),
                    evalTokens = perf[3].toLong(),
                    promptMs = perf[0].toLong(),
                    evalMs = perf[1].toLong(),
                )
            }
            config.log.log(
                LogProvider.Level.INFO, TAG,
                "generation: eval=${perf[3].toLong()} tok in ${"%.0f".format(perf[1])} ms" +
                    " (${"%.1f".format(perf[3] * 1000.0 / perf[1].coerceAtLeast(1.0))} tok/s)," +
                    " prompt=${perf[2].toLong()} tok in ${"%.0f".format(perf[0])} ms",
            )
        }
        close()
    }

    override suspend fun tokenize(handle: ModelHandle, text: String): List<Int> =
        NativeLlama.nativeTokenize(handle, text)?.toList() ?: emptyList()

    override suspend fun embeddings(handle: ModelHandle, text: String): FloatArray {
        throw UnsupportedOperationException("llama runtime does not expose embeddings in this build")
    }

    override suspend fun stop(handle: ModelHandle) {
        NativeLlama.nativeStop(handle)
    }

    companion object {
        private const val TAG = "EdgeDroid.LlamaRuntime"
    }
}

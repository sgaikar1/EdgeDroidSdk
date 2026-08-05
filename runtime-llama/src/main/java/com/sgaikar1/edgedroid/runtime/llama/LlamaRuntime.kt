package com.sgaikar1.edgedroid.runtime.llama

import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.common.Token
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.ModelHandle
import com.sgaikar1.edgedroid.core.Runtime
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Concrete [Runtime] over llama.cpp. Implements only the interface — the SDK never reaches
 * into llama specifics through it.
 */
internal class LlamaRuntime(private val config: RuntimeConfig) : Runtime {

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Uninitialized)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var initialized = false
    private val tokenCounter = AtomicLong(0)

    override suspend fun initialize() {
        if (initialized) return
        NativeLlama.nativeInit()
        initialized = true
        _state.value = RuntimeState.Initialized
        config.log.log(LogProvider.Level.INFO, TAG, "llama backend initialized")
    }

    override suspend fun loadModel(model: Model, options: RuntimeConfig): ModelHandle {
        val path = model.localPath ?: throw IllegalArgumentException(
            "Model '${model.id}' has no local path — ensure it is downloaded before load",
        )
        val handle = NativeLlama.nativeLoadModel(
            path = path,
            nCtx = options.memory.contextSize,
            nThreads = options.threading.threads,
            nThreadsBatch = options.threading.batchThreads,
            nBatch = options.memory.batchSize,
            nGpuLayers = options.memory.gpuLayers,
            mmap = options.memory.mmap,
        )
        if (handle == 0L) throw RuntimeException("Failed to load model at $path")
        _state.value = RuntimeState.ModelLoaded
        return handle
    }

    override suspend fun unload(handle: ModelHandle) {
        if (handle != 0L) NativeLlama.nativeUnload(handle)
        _state.value = RuntimeState.Initialized
    }

    override suspend fun generate(
        handle: ModelHandle,
        prompt: String,
        options: GenerationOptions,
    ): Flow<Token> = callbackFlow {
        var index = 0L
        val callback = NativeLlama.TokenCallback { text ->
            trySend(Token(index = index, id = tokenCounter.getAndIncrement(), text = text))
            index++
        }
        withContext(Dispatchers.Default) {
            NativeLlama.nativeGenerate(
                handle = handle,
                prompt = prompt,
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

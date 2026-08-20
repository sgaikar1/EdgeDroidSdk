package com.sgaikar1.edgedroid.core

import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.GenerationStats
import com.sgaikar1.edgedroid.common.Token
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Opaque handle to a loaded model. Native pointers never leave a runtime; the SDK only moves
 * this token around.
 */
typealias ModelHandle = Long

sealed interface RuntimeState {
    data object Uninitialized : RuntimeState
    data object Initialized : RuntimeState
    data object ModelLoaded : RuntimeState
    data class Error(val kind: String, val message: String) : RuntimeState
}

/**
 * The one interface every inference runtime implements. Notice: no llama.cpp, no GGUF, no JNI.
 * A runtime is a pure input → stream-of-tokens machine.
 */
interface Runtime {
    suspend fun initialize()
    suspend fun loadModel(model: Model, options: RuntimeConfig): ModelHandle
    suspend fun unload(handle: ModelHandle)
    suspend fun generate(handle: ModelHandle, prompt: PromptProcessor.PromptParts, options: GenerationOptions): Flow<Token>
    suspend fun tokenize(handle: ModelHandle, text: String): List<Int>
    suspend fun embeddings(handle: ModelHandle, text: String): FloatArray
    suspend fun stop(handle: ModelHandle)
    val state: StateFlow<RuntimeState>

    /**
     * Aggregate generation statistics for this runtime session (since the model was loaded).
     * Counters are updated as a side effect of [generate]; values for the current/last
     * generation are visible once its flow completes. Runtimes with native timing support
     * (e.g. llama.cpp `llama_perf_*`) report real decode/eval times; others fall back to
     * wall-clock measurements.
     */
    fun stats(): GenerationStats
}

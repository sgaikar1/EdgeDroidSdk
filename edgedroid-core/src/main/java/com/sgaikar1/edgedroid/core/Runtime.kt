package com.sgaikar1.edgedroid.core

import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.Token
import com.sgaikar1.edgedroid.common.TranscriptionOptions
import com.sgaikar1.edgedroid.common.TranscriptionResult
import com.sgaikar1.edgedroid.common.TranscriptionSegment
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
     * Transcribe [pcm] (16-bit little-endian PCM at [sampleRate] Hz) into timestamped
     * segments + full text. Only supported by runtimes declaring [Capability.AUDIO];
     * other runtimes throw [UnsupportedOperationException]. Default implementation so
     * existing text runtimes are unaffected.
     */
    suspend fun transcribe(
        handle: ModelHandle,
        pcm: ByteArray,
        sampleRate: Int,
        options: TranscriptionOptions = TranscriptionOptions.DEFAULT,
    ): TranscriptionResult {
        throw UnsupportedOperationException("runtime does not support audio transcription")
    }

    /**
     * Same as [transcribe], but emits each segment as it is finalized (partial/streaming
     * results where the backend supports them). Default implementation so existing text
     * runtimes are unaffected.
     */
    fun transcribeStream(
        handle: ModelHandle,
        pcm: ByteArray,
        sampleRate: Int,
        options: TranscriptionOptions = TranscriptionOptions.DEFAULT,
    ): Flow<TranscriptionSegment> {
        throw UnsupportedOperationException("runtime does not support streaming transcription")
    }
}

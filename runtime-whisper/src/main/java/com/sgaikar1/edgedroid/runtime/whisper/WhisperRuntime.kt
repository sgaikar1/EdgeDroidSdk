package com.sgaikar1.edgedroid.runtime.whisper

import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.common.Token
import com.sgaikar1.edgedroid.common.TranscriptionOptions
import com.sgaikar1.edgedroid.common.TranscriptionResult
import com.sgaikar1.edgedroid.common.TranscriptionSegment
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Concrete [Runtime] over whisper.cpp. Implements only the interface — the SDK never reaches
 * into whisper specifics through it. Text generation is not supported; the runtime is purely
 * an audio-transcription machine.
 */
internal class WhisperRuntime(private val config: RuntimeConfig) : Runtime {

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Uninitialized)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var initialized = false

    override suspend fun initialize() {
        if (initialized) return
        withContext(Dispatchers.Default) {
            NativeWhisper.nativeInit()
        }
        initialized = true
        _state.value = RuntimeState.Initialized
        config.log.log(LogProvider.Level.INFO, TAG, "whisper backend initialized")
    }

    override suspend fun loadModel(model: Model, options: RuntimeConfig): ModelHandle {
        val path = model.localPath ?: throw IllegalArgumentException(
            "Model '${model.id}' has no local path — ensure it is downloaded before load",
        )
        val threads = options.threading.threads
        val handle = withContext(Dispatchers.Default) {
            NativeWhisper.nativeLoadModel(path, threads)
        }
        if (handle == 0L) throw RuntimeException("Failed to load model at $path")
        _state.value = RuntimeState.ModelLoaded
        return handle
    }

    override suspend fun unload(handle: ModelHandle) {
        if (handle != 0L) withContext(Dispatchers.Default) { NativeWhisper.nativeUnload(handle) }
        _state.value = RuntimeState.Initialized
    }

    override suspend fun generate(
        handle: ModelHandle,
        prompt: PromptProcessor.PromptParts,
        options: GenerationOptions,
    ): Flow<Token> = flow {
        throw UnsupportedOperationException("whisper runtime does not support text generation")
    }

    override suspend fun tokenize(handle: ModelHandle, text: String): List<Int> {
        throw UnsupportedOperationException("whisper runtime does not support tokenization")
    }

    override suspend fun embeddings(handle: ModelHandle, text: String): FloatArray {
        throw UnsupportedOperationException("whisper runtime does not expose embeddings")
    }

    override suspend fun stop(handle: ModelHandle) {
        NativeWhisper.nativeStop(handle)
    }

    override suspend fun transcribe(
        handle: ModelHandle,
        pcm: ByteArray,
        sampleRate: Int,
        options: TranscriptionOptions,
    ): TranscriptionResult {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val segments = mutableListOf<TranscriptionSegment>()
        val ok = withContext(Dispatchers.Default) {
            NativeWhisper.nativeTranscribe(
                handle = handle,
                pcm = pcm,
                sampleRate = sampleRate,
                language = options.language,
                nThreads = options.threads,
                temperature = options.temperature,
                initialPrompt = options.initialPrompt,
                maxSegmentChars = options.maxSegmentChars,
                singleSegment = options.singleSegment,
                translate = options.translate,
                callback = { startMs, endMs, text ->
                    segments.add(TranscriptionSegment(startMs, endMs, text))
                },
            )
        }
        if (!ok) throw RuntimeException("whisper transcription failed")
        val language = withContext(Dispatchers.Default) { NativeWhisper.nativeLanguage(handle) }
        return TranscriptionResult(
            text = segments.joinToString("") { it.text },
            segments = segments,
            language = language,
        )
    }

    override fun transcribeStream(
        handle: ModelHandle,
        pcm: ByteArray,
        sampleRate: Int,
        options: TranscriptionOptions,
    ): Flow<TranscriptionSegment> = callbackFlow {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val ok = withContext(Dispatchers.Default) {
            NativeWhisper.nativeTranscribe(
                handle = handle,
                pcm = pcm,
                sampleRate = sampleRate,
                language = options.language,
                nThreads = options.threads,
                temperature = options.temperature,
                initialPrompt = options.initialPrompt,
                maxSegmentChars = options.maxSegmentChars,
                singleSegment = options.singleSegment,
                translate = options.translate,
                callback = { startMs, endMs, text ->
                    trySend(TranscriptionSegment(startMs, endMs, text))
                },
            )
        }
        if (!ok) throw RuntimeException("whisper transcription failed")
        close()
    }

    companion object {
        private const val TAG = "EdgeDroid.WhisperRuntime"
    }
}
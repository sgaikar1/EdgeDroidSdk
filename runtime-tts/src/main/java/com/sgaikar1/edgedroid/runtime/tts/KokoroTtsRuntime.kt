package com.sgaikar1.edgedroid.runtime.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.common.Token
import com.sgaikar1.edgedroid.common.TtsAudio
import com.sgaikar1.edgedroid.common.TtsSpeech
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/**
 * Kokoro-82M text-to-speech runtime. Implements [TtsSpeech] (Capability.AUDIO) alongside the
 * common [Runtime] lifecycle so it can be driven through the SDK's runtime registry without any
 * core changes. Chat/embedding methods throw [UnsupportedOperationException].
 *
 * Inference is a single non-autoregressive ONNX session: feed `input_ids` (phoneme token ids),
 * a length-indexed `style` vector and a `speed` scalar; the model returns raw 24 kHz mono audio.
 */
class KokoroTtsRuntime(private val config: RuntimeConfig) : Runtime, TtsSpeech {

    override val sampleRate: Int = 24_000

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Uninitialized)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputIdsName = "input_ids"
    private var styleName = "style"
    private var speedName = "speed"

    /** Cache of parsed voice files, keyed by absolute path. */
    private val voiceCache = HashMap<String, Array<FloatArray>>()
    private var voicesDir: String? = null
    private var voicePath: String? = null

    override suspend fun initialize() {
        if (env != null) return
        withContext(Dispatchers.Default) {
            env = OrtEnvironment.getEnvironment()
        }
        _state.value = RuntimeState.Initialized
        config.log.log(LogProvider.Level.INFO, TAG, "Kokoro TTS runtime initialized ($sampleRate Hz)")
    }

    override suspend fun loadModel(model: Model, options: RuntimeConfig): ModelHandle {
        val path = model.localPath ?: throw IllegalArgumentException(
            "TTS model '${model.id}' has no local path — ensure it is downloaded before load",
        )
        voicesDir = model.metadata["voicesDir"]
        voicePath = model.metadata["voicePath"]
        return withContext(Dispatchers.Default) {
            val e = env ?: throw IllegalStateException("Runtime not initialized")
            val opts = buildSessionOptions(options)
            session?.close()
            session = e.createSession(path, opts)
            bindInputNames(session!!)
            config.log.log(LogProvider.Level.INFO, TAG, "Kokoro TTS session created for '${model.id}'")
            1L
        }
    }

    private fun buildSessionOptions(options: RuntimeConfig): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(options.threading.threads)
        runCatching { opts.addXnnpack(mapOf("intra_op_num_threads" to options.threading.threads.toString())) }
            .onFailure {
                config.log.log(LogProvider.Level.WARN, TAG, "XNNPACK unavailable, using CPU: ${it.message}")
            }
        return opts
    }

    /** Bind the model's actual input names (robust to export naming differences). */
    private fun bindInputNames(s: OrtSession) {
        val names = s.inputNames
        inputIdsName = names.firstOrNull { it.lowercase().contains("input") } ?: names.first()
        styleName = names.firstOrNull { it.lowercase().contains("style") } ?: inputIdsName
        speedName = names.firstOrNull { it.lowercase().contains("speed") } ?: inputIdsName
    }

    override suspend fun unload(handle: ModelHandle) {
        withContext(Dispatchers.Default) {
            session?.close()
            session = null
            voiceCache.clear()
        }
        _state.value = RuntimeState.Initialized
    }

    override suspend fun synthesize(text: String, voice: String, speed: Float): TtsAudio {
        val s = session ?: throw IllegalStateException("TTS model not loaded")
        val e = env ?: throw IllegalStateException("Runtime not initialized")
        val safe = text.ifBlank { " " }
        val phonemes = EnG2P.phonemize(safe)
        val tokenIds = KokoroVocab.toIds(phonemes)
        if (tokenIds.isEmpty()) {
            throw IllegalArgumentException("Text produced no speakable phonemes: \"$text\"")
        }
        // Pad with the pad token (0) at both ends, like the reference export.
        val inputIds = longArrayOf(0L) + tokenIds.map { it.toLong() } + longArrayOf(0L)
        val style = styleTensor(voice, tokenIds.size)
        return withContext(Dispatchers.Default) {
            val inputIdsTensor = OnnxTensor.createTensor(e, arrayOf(inputIds))
            val styleTensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(style), longArrayOf(1L, 256L))
            val speedTensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1L))
            val feeds = HashMap<String, OnnxTensor>()
            feeds[inputIdsName] = inputIdsTensor
            feeds[styleName] = styleTensor
            feeds[speedName] = speedTensor
            val outputs = try {
                s.run(feeds)
            } finally {
                inputIdsTensor.close(); styleTensor.close(); speedTensor.close()
            }
            try {
                val out = outputs.get(s.outputNames.first()).get() as OnnxTensor
                val buf = out.floatBuffer
                val samples = FloatArray(buf.remaining())
                buf.get(samples)
                config.log.log(
                    LogProvider.Level.INFO, TAG,
                    "Synthesized ${samples.size} samples (~${samples.size * 1000L / sampleRate} ms) for " +
                        "${tokenIds.size} phoneme tokens, voice=$voice",
                )
                TtsAudio(samples, sampleRate)
            } finally {
                outputs.close()
            }
        }
    }

    /** Resolve and cache the requested voice's style matrix, or fail with a clear message. */
    private fun styleTensor(voice: String, tokenCount: Int): FloatArray {
        val file = resolveVoiceFile(voice)
        val rows = voiceCache.getOrPut(file.absolutePath) { KokoroVoice.load(file) }
        return KokoroVoice.styleTensor(rows, tokenCount)
    }

    private fun resolveVoiceFile(voice: String): File {
        val explicit = voicePath
        if (explicit != null) {
            val f = File(explicit)
            if (f.isFile) return f
            config.log.log(LogProvider.Level.WARN, TAG, "voicePath not found: $explicit — falling back to voicesDir")
        }
        val dir = voicesDir ?: throw IllegalStateException(
            "No voice available. Provide Model.metadata['voicesDir'] (a dir of '<voice>.bin' files) " +
                "or Model.metadata['voicePath'] (a single voice .bin).",
        )
        val f = File(dir, "$voice.bin")
        if (!f.isFile) {
            throw IllegalArgumentException(
                "Voice '$voice' not found in $dir. Known voices (see docs): af_heart, af_bella, " +
                    "am_adam, am_michael, bf_emma, bm_george, ef_dora, em_alex, jf_alpha, jm_kumo, ...",
            )
        }
        return f
    }

    // ---- unused chat/embedding surface (not supported by a TTS runtime) ----

    override suspend fun generate(
        handle: ModelHandle,
        prompt: PromptProcessor.PromptParts,
        options: GenerationOptions,
    ): Flow<Token> = emptyFlow()

    override suspend fun tokenize(handle: ModelHandle, text: String): List<Int> =
        throw UnsupportedOperationException("Kokoro TTS does not expose a chat tokenizer")

    override suspend fun embeddings(handle: ModelHandle, text: String): FloatArray =
        throw UnsupportedOperationException("Kokoro TTS does not support embeddings")

    override suspend fun stop(handle: ModelHandle) = Unit

    companion object {
        private const val TAG = "EdgeDroid.KokoroTts"
    }
}

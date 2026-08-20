package com.sgaikar1.edgedroid.api

import android.content.Context
import com.sgaikar1.edgedroid.common.FailureKind
import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.common.SdkResult
import com.sgaikar1.edgedroid.common.Token
import com.sgaikar1.edgedroid.common.TtsAudio
import com.sgaikar1.edgedroid.common.TtsSpeech
import com.sgaikar1.edgedroid.core.LlmEngine
import com.sgaikar1.edgedroid.core.LlmEngineState
import com.sgaikar1.edgedroid.core.MemoryConfig
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.ModelDownloadState
import com.sgaikar1.edgedroid.core.ModelProvider
import com.sgaikar1.edgedroid.core.PromptProcessor
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimePlugin
import com.sgaikar1.edgedroid.core.ThreadingConfig
import com.sgaikar1.edgedroid.download.DownloadConfig
import com.sgaikar1.edgedroid.download.DownloadManager
import com.sgaikar1.edgedroid.storage.ModelStorageImpl
import com.sgaikar1.edgedroid.storage.StoragePaths
import com.sgaikar1.edgedroid.api.internal.AndroidDeviceCapabilities
import com.sgaikar1.edgedroid.api.internal.DefaultCompatibilityChecker
import com.sgaikar1.edgedroid.api.internal.InternalModelProvider
import com.sgaikar1.edgedroid.api.internal.SdkEngine
import com.sgaikar1.edgedroid.core.Capability
import com.sgaikar1.edgedroid.core.CompatibilityChecker
import com.sgaikar1.edgedroid.core.CompatibilityReport
import com.sgaikar1.edgedroid.core.DeviceCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import java.io.File

/**
 * The single entry point an Android developer sees. It owns everything except inference:
 * downloads, storage, runtime selection, sessions, streaming, threading.
 */
class EdgeDroid private constructor(
    private val engine: SdkEngine,
    private val provider: ModelProvider,
    private val registry: RuntimeRegistry,
    private val compatibilityChecker: CompatibilityChecker,
    private val log: LogProvider,
    private val deviceCapabilities: DeviceCapabilities,
) {

    /** Observable engine lifecycle state (Idle → Loading → Ready → Generating …). */
    val state: StateFlow<LlmEngineState> = engine.state

    /**
     * Hardware snapshot of the device this SDK was built on. Apps use it to pre-filter
     * downloadable models and to pick sane defaults before a download.
     */
    val capabilities: DeviceCapabilities
        get() = deviceCapabilities

    /** Model acquisition / management surface. */
    val models: ModelFacade = ModelFacade()

    /**
     * Load the configured model, downloading it first if necessary.
     */
    suspend fun load() = engine.load()

    /**
     * Unload the model and release the runtime.
     */
    suspend fun unload() = engine.unload()

    /**
     * Adopt an already-on-device model file (no download, no metadata required) and load it.
     */
    suspend fun loadModel(file: File): Model {
        val model = provider.adoptLocal(file.name, file.absolutePath)
        engine.switchModel(model)
        engine.load()
        return model
    }

    /**
     * Stream a completion as a cold [Flow] of [Token]. The SDK handles session history,
     * prompt rendering, runtime selection and threading. [images] are forwarded to vision-capable
     * runtimes; text-only runtimes ignore them.
     */
    fun stream(
        prompt: String,
        images: List<PromptProcessor.PromptAttachment> = emptyList(),
        options: GenerationOptions = GenerationOptions.DEFAULT,
    ): Flow<Token> = engine.stream(prompt, images, options)

    /**
     * Convenience streaming with a callback. Suspend until generation finishes.
     */
    suspend fun stream(
        prompt: String,
        images: List<PromptProcessor.PromptAttachment> = emptyList(),
        options: GenerationOptions = GenerationOptions.DEFAULT,
        onToken: (Token) -> Unit,
    ) {
        engine.stream(prompt, images, options).collect(onToken)
    }

    /**
     * Non-streaming completion returning the full text.
     */
    suspend fun generate(
        prompt: String,
        images: List<PromptProcessor.PromptAttachment> = emptyList(),
        options: GenerationOptions = GenerationOptions.DEFAULT,
    ): String = engine.generate(prompt, images, options)

    /**
     * Embedding vector for [text] using the loaded model. Requires the selected runtime to
     * support [Capability.EMBEDDINGS] (e.g. the ONNX runtime with an embedding model); other
     * runtimes throw [UnsupportedOperationException].
     */
    suspend fun embeddings(text: String): FloatArray = engine.embeddings(text)

    /**
     * True when an AUDIO (text-to-speech) runtime was configured via [Builder.tts]. Guards the
     * [speak] / [synthesize] / [synthesizeWav] calls so apps can hide the voice UI otherwise.
     */
    val ttsAvailable: Boolean
        get() = engine.ttsAvailable

    /**
     * Synthesize [text] to audio using the configured TTS runtime (Capability.AUDIO), then play
     * it back with a float `AudioTrack`. Returns the synthesized [TtsAudio] (raw 24 kHz mono
     * samples) so the caller can also save it as WAV via [TtsAudio.toWav].
     *
     * Requires a TTS plugin+model registered via [Builder.tts]; otherwise throws
     * [UnsupportedOperationException].
     */
    suspend fun speak(
        text: String,
        voice: String = TtsSpeech.DEFAULT_VOICE,
        speed: Float = 1f,
    ): TtsAudio {
        val audio = engine.synthesize(text, voice, speed)
        com.sgaikar1.edgedroid.api.internal.AudioPlayer.play(audio)
        return audio
    }

    /**
     * Synthesize [text] and return raw samples without playing them. See [speak].
     */
    suspend fun synthesize(
        text: String,
        voice: String = TtsSpeech.DEFAULT_VOICE,
        speed: Float = 1f,
    ): TtsAudio = engine.synthesize(text, voice, speed)

    /**
     * Synthesize [text] and return a complete 16-bit PCM WAV file (RIFF) at the model's native
     * sample rate (24 kHz for Kokoro). See [speak].
     */
    suspend fun synthesizeWav(
        text: String,
        voice: String = TtsSpeech.DEFAULT_VOICE,
        speed: Float = 1f,
    ): ByteArray = engine.synthesize(text, voice, speed).toWav()

    /**
     * Interrupt an in-flight generation.
     */
    suspend fun stop() = engine.stop()

    /**
     * Register an additional runtime at any time — the core SDK is never modified.
     */
    fun registerRuntime(plugin: RuntimePlugin) {
        registry.register(plugin)
    }

    /** System prompt used by the default chat session. */
    var systemPrompt: String?
        get() = engine.systemPrompt
        set(value) {
            if (value == null) {
                engine.resetSession()
            } else {
                engine.setSystemPrompt(value)
            }
        }

    /** Clear conversation history. */
    fun resetChat() = engine.resetSession()

    /**
     * Model acquisition / management surface.
     */
    inner class ModelFacade {
        fun download(): Flow<ModelDownloadState> {
            val model = engine.currentModel
                ?: throw IllegalStateException("No model configured — pass .model(...) to the builder")
            return provider.download(model)
        }

        fun download(model: Model): Flow<ModelDownloadState> = provider.download(model)

        fun available(): List<Model> = provider.availableModels()

        fun resolve(id: String): Model? = provider.resolve(id)

        suspend fun delete(modelId: String) {
            engine.unload()
            provider.delete(modelId)
        }

        /**
         * Check whether this device can download and run the given model before committing
         * to a (potentially large) download. Hard errors mean it will fail; warnings are
         * advisory (slow/unsupported-but-may-work). Safe to call on the main thread.
         */
        fun checkCompatibility(
            model: Model = engine.currentModel
                ?: throw IllegalStateException("No model configured — pass .model(...) to the builder"),
            requiredCapabilities: Set<Capability> = emptySet(),
        ): CompatibilityReport = compatibilityChecker.check(model, requiredCapabilities)
    }

    class Builder(context: Context) {
        private val appContext = context.applicationContext
        private var runtimeSpec: RuntimeSpec = RuntimeSpec.Auto
        private var model: Model? = null
        private val downloadConfig = DownloadConfig.Builder()
        private val threading = ThreadingConfig.Builder()
        private val memory = MemoryConfig.Builder()
        private var logProvider: LogProvider = LogProvider.NO_OP
        private val plugins = mutableListOf<RuntimePlugin>()
        private val extras = mutableMapOf<String, Any>()
        private var ttsPlugin: RuntimePlugin? = null
        private var ttsModel: Model? = null

        fun runtime(spec: RuntimeSpec): Builder = apply { this.runtimeSpec = spec }
        fun model(model: Model): Builder = apply { this.model = model }
        fun download(block: DownloadConfig.Builder.() -> Unit): Builder =
            apply { downloadConfig.apply(block) }
        fun threading(block: ThreadingConfig.Builder.() -> Unit): Builder =
            apply { threading.apply(block) }
        fun memory(block: MemoryConfig.Builder.() -> Unit): Builder =
            apply { memory.apply(block) }

        /** Add or override a runtime-specific configuration knob (e.g. `executionProvider`). */
        fun extra(key: String, value: Any): Builder = apply { extras[key] = value }

        /** Replace the whole set of runtime-specific configuration knobs. */
        fun extras(map: Map<String, Any>): Builder = apply {
            extras.clear()
            extras.putAll(map)
        }

        fun logging(provider: LogProvider): Builder = apply { this.logProvider = provider }
        fun registerRuntime(plugin: RuntimePlugin): Builder = apply { plugins.add(plugin) }

        /**
         * Register a text-to-speech runtime and its model, enabling [speak]/[synthesize]/
         * [synthesizeWav]. [plugin] must declare [Capability.AUDIO] (e.g.
         * `KokoroTtsPlugin()`); [model] is the downloaded ONNX TTS model, with voice files
         * located via `Model.metadata['voicesDir']` or `Model.metadata['voicePath']`.
         */
        fun tts(plugin: RuntimePlugin, model: Model): Builder = apply {
            this.ttsPlugin = plugin
            this.ttsModel = model
        }

        fun build(): EdgeDroid {
            val log = logProvider
            val spec = runtimeSpec
            val paths = StoragePaths(appContext)
            val storage = ModelStorageImpl(paths, log)

            val registry = RuntimeRegistry(log)
            plugins.forEach { registry.register(it) }
            if (spec is RuntimeSpec.ByPlugin) {
                registry.register(spec.plugin)
            }

            val deviceCapabilities = AndroidDeviceCapabilities(appContext).get()
            val memoryConfig = memory.build()
            val checker = DefaultCompatibilityChecker(
                deviceCapabilities, storage, registry, spec,
                gpuConfig = memoryConfig.gpu,
                log = log,
            )

            // Fail fast before touching the network if the download cannot possibly succeed.
            val downloader = DownloadManager(
                storage = storage,
                config = downloadConfig.build(),
                log = log,
                preflight = { model ->
                    checker.check(model).errors.firstOrNull()?.let {
                        ModelDownloadState.Failed(kind = it.code, message = it.message)
                    }
                },
                context = appContext,
            )

            val provider = InternalModelProvider(storage, downloader, log)
            val runtimeConfig = RuntimeConfig(
                threading = threading.build(),
                memory = memoryConfig,
                log = log,
                extras = extras,
            )

            lateinit var engine: SdkEngine
            engine = SdkEngine(
                model = model,
                provider = provider,
                config = runtimeConfig,
                createRuntime = {
                    val m = requireNotNull(engine.currentModel) {
                        "No model configured before runtime creation"
                    }
                    when (val result = RuntimeSelector.select(registry, spec, m, runtimeConfig)) {
                        is SdkResult.Success -> result.value
                        is SdkResult.Failure -> throw RuntimeException(result.message)
                    }
                },
                compatibilityGate = { m ->
                    checker.check(m).errors.firstOrNull()?.message
                },
                log = log,
                ttsPlugin = ttsPlugin,
                ttsModel = ttsModel,
            )

            log.log(LogProvider.Level.INFO, "EdgeDroid", "EdgeDroid SDK built")
            return EdgeDroid(engine, provider, registry, checker, log, deviceCapabilities)
        }
    }

    companion object {
        val DEFAULT_OPTIONS: GenerationOptions = GenerationOptions.DEFAULT

        /**
         * Read this device's hardware capabilities without building a full SDK. Lets an app
         * pick device-appropriate defaults (threads, context, GPU policy) up front.
         */
        fun deviceCapabilities(context: Context): DeviceCapabilities =
            AndroidDeviceCapabilities(context).get()
    }
}

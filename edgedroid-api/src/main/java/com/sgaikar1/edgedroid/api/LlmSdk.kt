package com.sgaikar1.edgedroid.api

import android.content.Context
import com.sgaikar1.edgedroid.common.FailureKind
import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.common.SdkResult
import com.sgaikar1.edgedroid.common.Token
import com.sgaikar1.edgedroid.core.LlmEngine
import com.sgaikar1.edgedroid.core.LlmEngineState
import com.sgaikar1.edgedroid.core.MemoryConfig
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.ModelDownloadState
import com.sgaikar1.edgedroid.core.ModelProvider
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimePlugin
import com.sgaikar1.edgedroid.core.ThreadingConfig
import com.sgaikar1.edgedroid.download.DownloadConfig
import com.sgaikar1.edgedroid.download.DownloadManager
import com.sgaikar1.edgedroid.storage.ModelStorageImpl
import com.sgaikar1.edgedroid.storage.StoragePaths
import com.sgaikar1.edgedroid.api.internal.InternalModelProvider
import com.sgaikar1.edgedroid.api.internal.SdkEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import java.io.File

/**
 * The single entry point an Android developer sees. It owns everything except inference:
 * downloads, storage, runtime selection, sessions, streaming, threading.
 */
class LlmSdk private constructor(
    private val engine: SdkEngine,
    private val provider: ModelProvider,
    private val registry: RuntimeRegistry,
    private val log: LogProvider,
) {

    /** Observable engine lifecycle state (Idle → Loading → Ready → Generating …). */
    val state: StateFlow<LlmEngineState> = engine.state

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
     * prompt rendering, runtime selection and threading.
     */
    fun stream(prompt: String, options: GenerationOptions = GenerationOptions.DEFAULT): Flow<Token> =
        engine.stream(prompt, options)

    /**
     * Convenience streaming with a callback. Suspend until generation finishes.
     */
    suspend fun stream(
        prompt: String,
        options: GenerationOptions = GenerationOptions.DEFAULT,
        onToken: (Token) -> Unit,
    ) {
        engine.stream(prompt, options).collect(onToken)
    }

    /**
     * Non-streaming completion returning the full text.
     */
    suspend fun generate(prompt: String, options: GenerationOptions = GenerationOptions.DEFAULT): String =
        engine.generate(prompt, options)

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

        fun runtime(spec: RuntimeSpec): Builder = apply { this.runtimeSpec = spec }
        fun model(model: Model): Builder = apply { this.model = model }
        fun download(block: DownloadConfig.Builder.() -> Unit): Builder =
            apply { downloadConfig.apply(block) }
        fun threading(block: ThreadingConfig.Builder.() -> Unit): Builder =
            apply { threading.apply(block) }
        fun memory(block: MemoryConfig.Builder.() -> Unit): Builder =
            apply { memory.apply(block) }
        fun logging(provider: LogProvider): Builder = apply { this.logProvider = provider }
        fun registerRuntime(plugin: RuntimePlugin): Builder = apply { plugins.add(plugin) }

        fun build(): LlmSdk {
            val log = logProvider
            val spec = runtimeSpec
            val paths = StoragePaths(appContext)
            val storage = ModelStorageImpl(paths, log)
            val downloader = DownloadManager(storage, downloadConfig.build(), log)

            val registry = RuntimeRegistry(log)
            plugins.forEach { registry.register(it) }
            if (spec is RuntimeSpec.ByPlugin) {
                registry.register(spec.plugin)
            }

            val provider = InternalModelProvider(storage, downloader, log)
            val runtimeConfig = RuntimeConfig(
                threading = threading.build(),
                memory = memory.build(),
                log = log,
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
                log = log,
            )

            log.log(LogProvider.Level.INFO, "EdgeDroid", "EdgeDroid SDK built")
            return LlmSdk(engine, provider, registry, log)
        }
    }

    companion object {
        val DEFAULT_OPTIONS: GenerationOptions = GenerationOptions.DEFAULT
    }
}

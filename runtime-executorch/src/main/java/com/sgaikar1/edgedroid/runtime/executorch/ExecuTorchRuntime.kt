package com.sgaikar1.edgedroid.runtime.executorch

import android.graphics.BitmapFactory
import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.common.Token
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.ModelHandle
import com.sgaikar1.edgedroid.core.PromptProcessor
import com.sgaikar1.edgedroid.core.Runtime
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimeState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.pytorch.executorch.ExecuTorchRuntime
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmGenerationConfig
import org.pytorch.executorch.extension.llm.LlmModule
import org.pytorch.executorch.extension.llm.LlmModuleConfig

/**
 * Concrete [Runtime] over ExecuTorch's `LlmModule` extension. Implements only the SPI — the SDK
 * never reaches into ExecuTorch specifics through it.
 *
 * Streaming: [generate] bridges the native token callback into a cold [Flow] of [Token], exactly
 * like the llama runtime does for its JNI callback. Vision models use the multimodal runner
 * (`MODEL_TYPE_TEXT_VISION`) and are prefilled with decoded ARGB pixels before generation.
 */
internal class ExecuTorchRuntime(private val config: RuntimeConfig) : Runtime {

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Uninitialized)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var initialized = false
    private val tokenCounter = AtomicLong(0)
    private val handleCounter = AtomicLong(1)
    private val modules = ConcurrentHashMap<ModelHandle, LoadedModule>()

    private class LoadedModule(
        val module: LlmModule,
        val moduleConfig: ExecuTorchModuleConfig,
    )

    override suspend fun initialize() {
        if (initialized) return
        withContext(Dispatchers.Default) {
            // Loads libexecutorch.so via SoLoader (see ExecuTorchRuntime companion).
            ExecuTorchRuntime.getRuntime()
        }
        initialized = true
        _state.value = RuntimeState.Initialized
        config.log.log(LogProvider.Level.INFO, TAG, "ExecuTorch backend initialized")
    }

    override suspend fun loadModel(model: Model, options: RuntimeConfig): ModelHandle {
        val moduleConfig = ExecuTorchConfigTransformer.moduleConfig(model, options)
        val module = withContext(Dispatchers.Default) {
            val m = LlmModule(
                LlmModuleConfig.create()
                    .modulePath(moduleConfig.modulePath)
                    .tokenizerPath(moduleConfig.tokenizerPath)
                    .temperature(moduleConfig.temperature)
                    .dataPath(moduleConfig.dataPath)
                    .modelType(moduleConfig.modelType)
                    .loadMode(moduleConfig.loadMode)
                    .build(),
            )
            // Force the model load now so failures surface here, not on the first generate().
            m.load()
            m
        }
        val handle = handleCounter.getAndIncrement()
        modules[handle] = LoadedModule(module, moduleConfig)
        _state.value = RuntimeState.ModelLoaded
        config.log.log(
            LogProvider.Level.INFO, TAG,
            "Loaded ExecuTorch module '$model' (${moduleConfig.modulePath})",
        )
        return handle
    }

    override suspend fun unload(handle: ModelHandle) {
        val loaded = modules.remove(handle) ?: return
        withContext(Dispatchers.Default) {
            runCatching { loaded.module.stop() }
            runCatching { loaded.module.close() }
        }
        _state.value = RuntimeState.Initialized
    }

    override suspend fun generate(
        handle: ModelHandle,
        prompt: PromptProcessor.PromptParts,
        options: GenerationOptions,
    ): Flow<Token> = callbackFlow {
        val loaded = modules[handle]
            ?: throw IllegalStateException("No ExecuTorch module loaded for handle $handle")
        val module = loaded.module
        val generationConfig = ExecuTorchConfigTransformer.generationConfig(
            options = options,
            promptChars = prompt.render().length,
            contextSize = config.memory.contextSize,
        )

        var index = 0L
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val callback = object : LlmCallback {
            override fun onResult(result: String) {
                trySend(Token(index = index, id = tokenCounter.getAndIncrement(), text = result))
                index++
            }

            override fun onError(errorCode: Int, message: String) {
                failure.set(
                    RuntimeException(
                        "ExecuTorch generation failed (code $errorCode): $message",
                    ),
                )
            }
        }

        withContext(Dispatchers.Default) {
            val image = prompt.attachments.firstOrNull()
            if (image != null) {
                generateWithImage(module, generationConfig, prompt, image, options, callback)
            } else {
                module.generate(prompt.render(), generationConfig, callback)
            }
        }

        failure.get()?.let { throw it }
        close()
    }

    private fun generateWithImage(
        module: LlmModule,
        generationConfig: LlmGenerationConfig,
        prompt: PromptProcessor.PromptParts,
        image: PromptProcessor.PromptAttachment,
        options: GenerationOptions,
        callback: LlmCallback,
    ) {
        val bitmap = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
            ?: throw IllegalArgumentException("ExecuTorch vision: failed to decode attached image")
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        // ARGB_8888 pixels with 4 channels — the shape the multimodal runner's prefill expects.
        module.generate(
            image = pixels,
            width = width,
            height = height,
            channels = 4,
            prompt = prompt.render(),
            seqLen = generationConfig.seqLen,
            llmCallback = callback,
            echo = false,
            temperature = options.temperature,
            numBos = generationConfig.numBos,
            numEos = generationConfig.numEos,
        )
    }

    override suspend fun tokenize(handle: ModelHandle, text: String): List<Int> {
        // ExecuTorch's LlmModule tokenizes internally during generate(); it does not expose raw
        // token ids through the Android API. Callers that need ids should use the llama runtime.
        throw UnsupportedOperationException(
            "ExecuTorch runtime does not expose raw token ids through its Android API",
        )
    }

    override suspend fun embeddings(handle: ModelHandle, text: String): FloatArray {
        throw UnsupportedOperationException(
            "ExecuTorch LLM modules are generative; embeddings are not exposed in this build",
        )
    }

    override suspend fun stop(handle: ModelHandle) {
        modules[handle]?.module?.stop()
    }

    companion object {
        private const val TAG = "EdgeDroid.ExecuTorchRuntime"
    }
}
package com.sgaikar1.edgedroid.runtime.executorch

import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.RuntimeConfig
import org.pytorch.executorch.extension.llm.LlmGenerationConfig
import org.pytorch.executorch.extension.llm.LlmModule
import org.pytorch.executorch.extension.llm.LlmModuleConfig

/**
 * ExecuTorch-side module-executor configuration derived from a SDK [RuntimeConfig] + [Model].
 *
 * This is the "transformer" half of the `RuntimeConfig -> ExecuTorch module executor config`
 * bridge: the SDK exposes coarse threading/memory intent plus per-model metadata, and this layer
 * translates it into the exact knobs ExecuTorch's `LlmModule`/`LlmModuleConfig` understands.
 */
data class ExecuTorchModuleConfig(
    /** Absolute path to the `.pte` file on device. */
    val modulePath: String,
    /** Absolute path to the tokenizer file (`.bin`/`.json`) matching the export. */
    val tokenizerPath: String,
    /** Sampling temperature applied by the module executor. */
    val temperature: Float = 0.8f,
    /** `LlmModule.MODEL_TYPE_TEXT` or `LlmModule.MODEL_TYPE_TEXT_VISION`. */
    val modelType: Int = LlmModule.MODEL_TYPE_TEXT,
    /** Optional extra data file (e.g. vision preprocessor artifacts) passed to the executor. */
    val dataPath: String? = null,
    /** How the `.pte` file is loaded; honors [RuntimeConfig.memory.mmap] by default. */
    val loadMode: Int = LlmModuleConfig.LOAD_MODE_MMAP,
)

/**
 * Maps [RuntimeConfig] + model metadata -> ExecuTorch configs.
 *
 * Pure Kotlin and unit-testable on the JVM — no ExecuTorch native library is loaded here. The
 * derived [LlmGenerationConfig] instances are cached keyed by every input that influences them so
 * repeated generations with identical options reuse the same builder result instead of allocating
 * a new object (and re-deriving sequence lengths) every call.
 */
object ExecuTorchConfigTransformer {

    // Keys understood in Model.metadata (per-model) and RuntimeConfig.extras (per-SDK build).
    const val KEY_TOKENIZER_PATH = "tokenizerPath"
    const val KEY_DATA_PATH = "dataPath"
    const val KEY_MODEL_TYPE = "modelType"
    const val KEY_LOAD_MODE = "loadMode"
    const val KEY_TEMPERATURE = "temperature"

    // Bounded caches: generation keys include a prompt-derived seqLen (varies per prompt length),
    // so an unbounded cache would leak one entry per distinct prompt in a long session. The LRU
    // bounds memory and the runtime clears the caches on unload.
    private const val MAX_GENERATION_ENTRIES = 128
    private const val MAX_MODULE_ENTRIES = 16

    private val generationCache = BoundedLruCache<String, LlmGenerationConfig>(MAX_GENERATION_ENTRIES)
    private val moduleCache = BoundedLruCache<String, ExecuTorchModuleConfig>(MAX_MODULE_ENTRIES)

    /**
     * Build (and cache) the ExecuTorch module-executor config for [model] under [config].
     * Cached per model id + the memory/extra knobs that affect the executor.
     *
     * @throws IllegalArgumentException when the model has no local path or no tokenizer path.
     */
    fun moduleConfig(model: Model, config: RuntimeConfig): ExecuTorchModuleConfig {
        val cacheKey = buildString {
            append(model.id)
            append('|').append(model.metadata)
            append('|').append(config.memory.mmap)
            append('|').append(config.extras)
        }
        return moduleCache.getOrPut(cacheKey) {
            val modulePath = model.localPath ?: throw IllegalArgumentException(                "Model '${model.id}' has no local path — ensure it is downloaded before load",
            )
            val tokenizerPath = model.metadata[KEY_TOKENIZER_PATH]
                ?: config.extras[KEY_TOKENIZER_PATH] as? String
                ?: throw IllegalArgumentException(
                    "ExecuTorch model '${model.id}' requires a tokenizer path — set " +
                        "metadata[\"$KEY_TOKENIZER_PATH\"] (or extras[\"$KEY_TOKENIZER_PATH\"]). " +
                        "ExecuTorch LLM exports pair the .pte with a tokenizer file.",
                )
            ExecuTorchModuleConfig(
                modulePath = modulePath,
                tokenizerPath = tokenizerPath,
                temperature = (config.extras[KEY_TEMPERATURE] as? Number)?.toFloat()
                    ?: model.metadata[KEY_TEMPERATURE]?.toFloatOrNull()
                    ?: 0.8f,
                modelType = modelTypeFrom(model, config),
                dataPath = model.metadata[KEY_DATA_PATH] ?: config.extras[KEY_DATA_PATH] as? String,
                loadMode = loadModeFrom(config),
            )
        }
    }

    /**
     * Build (and cache) the [LlmGenerationConfig] for [options]. [promptChars] and [contextSize]
     * feed the sequence-length computation: ExecuTorch's `seqLen` is the *total* sequence length
     * (prompt + generated tokens), not just the new-token budget, so we size it from both.
     */
    fun generationConfig(
        options: GenerationOptions,
        promptChars: Int,
        contextSize: Int,
    ): LlmGenerationConfig {
        val estimatedPromptTokens = promptChars / 4 + 8 // ~4 chars/token upper bound
        // seqLen is the *total* sequence length (prompt + generated). Clamp it so it never
        // exceeds the context window, even when the caller requests more new tokens than fit.
        val seqLen = (estimatedPromptTokens + options.maxTokens).coerceIn(1, contextSize)
        val key = "$options|$seqLen"
        return generationCache.getOrPut(key) {
            LlmGenerationConfig.create()
                .temperature(options.temperature)
                .maxNewTokens(options.maxTokens)
                .seqLen(seqLen)
                .echo(false) // chat-style: do not echo the prompt back
                .build()
        }
    }

    /** Invalidate all cached configs (e.g. after a model unload with different metadata). */
    fun clearCache() {
        generationCache.clear()
        moduleCache.clear()
    }

    private fun modelTypeFrom(model: Model, config: RuntimeConfig): Int {
        val raw = model.metadata[KEY_MODEL_TYPE] ?: config.extras[KEY_MODEL_TYPE]
        return when (raw?.toString()?.trim()?.lowercase()) {
            "2", "vision", "multimodal", "text-vision" -> LlmModule.MODEL_TYPE_TEXT_VISION
            else -> LlmModule.MODEL_TYPE_TEXT
        }
    }

    private fun loadModeFrom(config: RuntimeConfig): Int =
        when (config.extras[KEY_LOAD_MODE]?.toString()?.trim()?.lowercase()) {
            "file" -> LlmModuleConfig.LOAD_MODE_FILE
            "mlock" -> LlmModuleConfig.LOAD_MODE_MMAP_USE_MLOCK
            "mlock-ignore-errors" -> LlmModuleConfig.LOAD_MODE_MMAP_USE_MLOCK_IGNORE_ERRORS
            // Default honors the SDK's coarse mmap intent: on by default, off when the caller
            // explicitly disables memory mapping.
            else -> if (config.memory.mmap) LlmModuleConfig.LOAD_MODE_MMAP else LlmModuleConfig.LOAD_MODE_FILE
        }
}

/**
 * Small, thread-safe LRU used to bound the derived-config caches so a long session never leaks
 * memory (evicts least-recently-used entries past [maxSize]).
 */
private class BoundedLruCache<K, V>(private val maxSize: Int) {

    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxSize
    }

    @Synchronized
    fun getOrPut(key: K, value: () -> V): V {
        map[key]?.let { return it }
        return value().also { map[key] = it }
    }

    @Synchronized
    fun clear() {
        map.clear()
    }
}
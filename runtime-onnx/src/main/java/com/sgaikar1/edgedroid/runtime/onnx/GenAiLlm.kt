package com.sgaikar1.edgedroid.runtime.onnx

import ai.onnxruntime.genai.Generator
import ai.onnxruntime.genai.GeneratorParams
import ai.onnxruntime.genai.Model
import ai.onnxruntime.genai.Tokenizer
import ai.onnxruntime.genai.TokenizerStream
import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.common.Token
import com.sgaikar1.edgedroid.core.PromptProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * ONNX Runtime GenAI LLM chat driver. Unlike the raw-ORT autoregressive loop in
 * [OnnxRuntime.generate], this keeps a single native [Generator] alive for the whole session so
 * the KV cache is reused across turns — context memory grows as the conversation grows instead of
 * re-decoding the full history every call.
 *
 * Model layout: GenAI loads a model *directory* (the `.onnx` weights plus a `genai_config.json`
 * and tokenizer). Point [load] at that directory.
 *
 * Turn semantics mirror the existing prefix-caching runtime (llama.cpp `nativeSetPrefix`): the
 * first call feeds the full rendered prompt; each later call appends only the *delta* (the current
 * user message + assistant opener) via [Generator.appendTokenSequences], so the accumulated
 * prefix stays in KV.
 *
 * Not thread-safe; the owning [OnnxRuntime] serialises generate calls through its coroutine scope.
 */
internal class GenAiLlm(private val log: LogProvider) {

    private var model: Model? = null
    private var tokenizer: Tokenizer? = null
    private var generator: Generator? = null
    private var stream: TokenizerStream? = null
    private var hasContext = false
    @Volatile private var stopRequested = false

    /** Load a GenAI model directory and prepare the tokenizer. Drops any previous session. */
    fun load(modelPath: String) {
        close()
        val m = Model(modelPath)
        model = m
        tokenizer = Tokenizer(m)
        hasContext = false
        log.log(LogProvider.Level.INFO, TAG, "ONNX Runtime GenAI model loaded from $modelPath")
    }

    fun unload() {
        close()
        hasContext = false
    }

    /** Drop the in-flight generator so the next [generate] starts a fresh KV cache. */
    fun resetSession() {
        generator?.close()
        generator = null
        stream?.close()
        stream = null
        hasContext = false
        stopRequested = false
    }

    fun stop() {
        stopRequested = true
    }

    /**
     * Stream a completion for one turn. The first call initialises the [GeneratorParams] and
     * feeds [PromptProcessor.PromptParts.render]; later calls append the per-turn delta
     * ([GenAiPrompts.userOpener] + [PromptProcessor.PromptParts.body]) to reuse the KV cache.
     */
    fun generate(
        parts: PromptProcessor.PromptParts,
        template: PromptProcessor.Template,
        options: GenerationOptions,
    ): Flow<Token> = callbackFlow {
        val m = model ?: throw IllegalStateException("GenAI model not loaded")
        val tok = tokenizer ?: throw IllegalStateException("GenAI tokenizer not loaded")

        val active = generator
        if (active == null || !hasContext) {
            // Fresh session: configure search options (from GenerationOptions) and seed the KV.
            val params = GeneratorParams(m).also {
                GenAiOptions.apply(it, options, log)
            }
            val g = Generator(m, params)
            params.close()
            g.appendTokenSequences(tok.encode(parts.render()))
            stream = tok.createStream()
            generator = g
            log.log(LogProvider.Level.INFO, TAG, "GenAI session started (KV seeded with full prompt)")
        } else {
            // Continuing turn: append only the delta; everything prior stays in KV.
            active.appendTokenSequences(tok.encode(GenAiPrompts.userOpener(template) + parts.body))
            log.log(LogProvider.Level.INFO, TAG, "GenAI turn continued (delta appended, KV reused)")
        }
        hasContext = true

        val s = stream ?: throw IllegalStateException("No tokenizer stream")
        val eosIds = runCatching { tok.getEosTokenIds() }.getOrNull()?.toSet() ?: emptySet()
        var index = 0L
        val emitted = StringBuilder()

        withContext(Dispatchers.Default) {
            val g = generator ?: throw IllegalStateException("No generator")
            try {
                var running = true
                while (running && !g.isDone()) {
                    if (stopRequested) {
                        running = false
                    } else {
                        g.generateNextToken()
                        val lastId = g.getLastTokenInSequence(0)
                        if (lastId in eosIds) {
                            running = false
                        } else {
                            val piece = runCatching { s.decode(lastId) }.getOrDefault("")
                            if (piece.isNotEmpty()) {
                                emitted.append(piece)
                                trySend(Token(index = index++, id = lastId.toLong(), text = piece))
                            }
                            // GenAI 0.15.2 has no stop_strings search option; honour GenerationOptions.stopSequences
                            // at the stream level and truncate once a sequence appears.
                            GenAiPrompts.matchStop(emitted, options.stopSequences)?.let { stopAt ->
                                emitted.setLength(stopAt)
                                running = false
                            }
                        }
                    }
                }
            } finally {
                stopRequested = false
            }
        }
        close()
    }

    private fun close() {
        generator?.close(); generator = null
        stream?.close(); stream = null
        tokenizer?.close(); tokenizer = null
        model?.close(); model = null
    }

    companion object {
        private const val TAG = "EdgeDroid.GenAiLlm"
    }
}

/**
 * Pure, JVM-testable mapping from [GenerationOptions] to GenAI search options. GenAI 0.15.2 does
 * not support a `stop_strings` search option, so stop sequences are handled at the stream level
 * (see [GenAiPrompts.matchStop]) rather than here.
 */
internal object GenAiOptions {

    /** The search options derived from [GenerationOptions]; [doSample] is set separately. */
    data class Mapped(
        val doSample: Boolean,
        val searchOptions: Map<String, Double>,
    )

    fun map(options: GenerationOptions): Mapped {
        // temperature <= 0 => greedy decoding (no sampling).
        val sampling = options.temperature > 0f
        val opts = LinkedHashMap<String, Double>()
        if (sampling && options.temperature != 1f) {
            opts["temperature"] = options.temperature.toDouble()
        }
        if (options.topK > 0) {
            opts["top_k"] = options.topK.toDouble()
        }
        if (options.topP > 0f && options.topP <= 1f) {
            opts["top_p"] = options.topP.toDouble()
        }
        if (options.repeatPenalty != 1f) {
            opts["repetition_penalty"] = options.repeatPenalty.toDouble()
        }
        if (options.seed >= 0) {
            opts["seed"] = options.seed.toDouble()
        }
        opts["max_length"] = options.maxTokens.toDouble()
        return Mapped(sampling, opts)
    }

    fun apply(params: GeneratorParams, options: GenerationOptions, log: LogProvider) {
        val mapped = map(options)
        params.setSearchOption("do_sample", mapped.doSample)
        mapped.searchOptions.forEach { (key, value) -> params.setSearchOption(key, value) }
        log.log(
            LogProvider.Level.INFO, "EdgeDroid.GenAiLlm",
            "GenAI params: do_sample=${mapped.doSample} ${mapped.searchOptions}",
        )
    }
}

/**
 * Pure, JVM-testable prompt-delta logic. The per-turn input a GenAI generator should be fed on a
 * continuing session is the template's current-user opener (the final chunk of the stable prefix)
 * plus the per-call body — the accumulated prefix lives in KV, so it must not be re-fed.
 */
internal object GenAiPrompts {

    /** The role opener the [PromptProcessor.Template] emits before the current user message. */
    fun userOpener(template: PromptProcessor.Template): String = when (template) {
        PromptProcessor.Template.CHATML, PromptProcessor.Template.QWEN -> "<|im_start|>user\n"
        PromptProcessor.Template.LLAMA -> "<|start_header_id|>user<|end_header_id|>\n\n"
        PromptProcessor.Template.RAW -> "User: "
    }

    /** The text actually fed to the generator for this turn. */
    fun turnInput(parts: PromptProcessor.PromptParts, template: PromptProcessor.Template, isFirstTurn: Boolean): String =
        if (isFirstTurn) parts.render() else userOpener(template) + parts.body

    /**
     * Return the index at which [accumulated] should be truncated (exclusive of any stop sequence)
     * if a [stopSequences] entry has been emitted, else null. The caller truncates with
     * `setLength(idx)` which drops the stop sequence and anything after it.
     */
    fun matchStop(accumulated: CharSequence, stopSequences: List<String>): Int? {
        if (stopSequences.isEmpty()) return null
        var earliest: Int? = null
        for (seq in stopSequences) {
            if (seq.isEmpty()) continue
            val idx = accumulated.indexOf(seq)
            if (idx >= 0 && (earliest == null || idx < earliest)) {
                earliest = idx
            }
        }
        return earliest
    }
}

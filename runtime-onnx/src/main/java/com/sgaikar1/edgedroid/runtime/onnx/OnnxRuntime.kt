package com.sgaikar1.edgedroid.runtime.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.common.Token
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
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/**
 * ONNX Runtime implementation. Currently supports embeddings (B1); LLM generation and vision
 * are the next slices (raw ORT autoregressive loop). The session is single-model — loadModel
 * replaces any previous session.
 */
internal class OnnxRuntime(private val config: RuntimeConfig) : Runtime {

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Uninitialized)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: HfTokenizer? = null
    private var embeddingDim = 384
    private var eosTokenId: Int? = null
    @Volatile private var stopRequested = false
    private var visionInputName: String? = null
    private var visionWidth = 224
    private var visionHeight = 224

    /** KV-cache chat driver used when the loaded model is an ONNX Runtime GenAI model. */
    private var genAi: GenAiLlm? = null

    override suspend fun initialize() {
        if (env != null) return
        withContext(Dispatchers.Default) {
            env = OrtEnvironment.getEnvironment()
        }
        _state.value = RuntimeState.Initialized
        config.log.log(LogProvider.Level.INFO, TAG, "ONNX Runtime initialized")
    }

    override suspend fun loadModel(model: Model, options: RuntimeConfig): ModelHandle {
        val path = model.localPath ?: throw IllegalArgumentException(
            "Model '${model.id}' has no local path — ensure it is downloaded before load",
        )
        val handle = withContext(Dispatchers.Default) {
            currentModelMetadata = model.metadata
            if (isGenAiModel(model, path)) {
                // ONNX Runtime GenAI chat model: KV-cache chat only. Drop any previous raw session.
                session?.close(); session = null
                tokenizer = null
                genAi?.unload()
                genAi = GenAiLlm(config.log).also { it.load(modelDir(path)) }
                1L
            } else {
                val e = env ?: throw IllegalStateException("Runtime not initialized")
                val sessionOptions = buildSessionOptions(options)
                val t0 = System.currentTimeMillis()
                val s = e.createSession(path, sessionOptions)
                config.log.log(LogProvider.Level.INFO, TAG, "OrtSession created in ${System.currentTimeMillis() - t0} ms")
                session?.close()
                session = s
                tokenizer = loadTokenizer(model, path)
                val tok = tokenizer
                eosTokenId = if (tok == null) null else {
                    val explicit = model.metadata["eosToken"]?.let(tok::tokenId)
                    explicit ?: tok.findEosTokenId()
                }
                detectVisionInput(s)
                1L
            }
        }
        _state.value = RuntimeState.ModelLoaded
        return handle
    }

    /**
     * A model is treated as ONNX Runtime GenAI when its metadata opts in via `genai=true`, or when
     * the local path is a directory that carries a GenAI manifest (`genai_config.json`/`config.json`).
     */
    private fun isGenAiModel(model: Model, path: String): Boolean {
        if (model.metadata["genai"] == "true") return true
        val dir = File(path)
        if (!dir.isDirectory) return false
        return File(dir, "genai_config.json").isFile || File(dir, "config.json").isFile
    }

    private fun modelDir(path: String): String {
        val f = File(path)
        return if (f.isDirectory) f.absolutePath else f.parentFile?.absolutePath ?: path
    }

    private fun buildSessionOptions(options: RuntimeConfig): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        val threads = options.threading.threads
        opts.setIntraOpNumThreads(threads)
        val ep = options.extras["executionProvider"] as? String ?: "XNNPACK"
        when (ep.uppercase()) {
            "NNAPI" -> runCatching { opts.addNnapi() }
                .onFailure { config.log.log(LogProvider.Level.WARN, TAG, "NNAPI unavailable, using CPU: ${it.message}") }
            "XNNPACK" -> runCatching { opts.addXnnpack(mapOf("intra_op_num_threads" to threads.toString())) }
                .onFailure { config.log.log(LogProvider.Level.WARN, TAG, "XNNPACK unavailable, using CPU: ${it.message}") }
        }
        return opts
    }

    private fun loadTokenizer(model: Model, modelPath: String): HfTokenizer? {
        val explicit = model.metadata["tokenizerPath"]
        val file = if (!explicit.isNullOrBlank()) File(explicit)
        else File(File(modelPath).parentFile, "tokenizer.json")
        if (!file.isFile) {
            config.log.log(LogProvider.Level.WARN, TAG, "No tokenizer.json found; set Model.metadata['tokenizerPath']")
            return null
        }
        return runCatching { HfTokenizer.fromJson(file.readText()) }
            .onFailure { config.log.log(LogProvider.Level.WARN, TAG, "Failed to parse tokenizer.json: ${it.message}") }
            .getOrNull()
    }

    /** Find a `pixel_values`-style float image input and its [1,3,H,W] shape. */
    private fun detectVisionInput(s: OrtSession) {
        visionInputName = null
        runCatching {
            s.getInputInfo().entries.firstOrNull { (name, _) ->
                name.contains("pixel_values") || name.contains("images") || name.contains("image_input")
            }?.let { (name, node) ->
                val tensor = node.info as? ai.onnxruntime.TensorInfo ?: return@let
                val shape = tensor.getShape()
                visionInputName = name
                if (shape != null && shape.size >= 4) {
                    visionHeight = shape[2].toInt().takeIf { it > 0 } ?: 224
                    visionWidth = shape[3].toInt().takeIf { it > 0 } ?: 224
                }
                config.log.log(
                    LogProvider.Level.INFO, TAG,
                    "Vision input '$name' detected [$visionHeight x $visionWidth]",
                )
            }
        }.onFailure {
            config.log.log(LogProvider.Level.WARN, TAG, "Vision input detection failed: ${it.message}")
        }
    }

    override suspend fun unload(handle: ModelHandle) {
        withContext(Dispatchers.Default) {
            session?.close()
            session = null
            tokenizer = null
            genAi?.unload()
            genAi = null
        }
        _state.value = RuntimeState.Initialized
    }

    override suspend fun generate(
        handle: ModelHandle,
        prompt: PromptProcessor.PromptParts,
        options: GenerationOptions,
    ): Flow<Token> {
        // KV-cache multi-turn chat path: GenAI keeps a persistent Generator whose KV cache grows
        // across turns. Raw-ORT embeddings + vision are unaffected (they use the non-GenAI path).
        genAi?.let { llm ->
            return llm.generate(prompt, template, options)
        }
        return generateNoKv(prompt, options)
    }

    /**
     * Raw-ORT (no-KV) autoregressive generation for non-GenAI models: full-context loop that
     * re-decodes the whole prompt every call. Also serves embeddings + vision models. Models that
     * require a `past_key_values` sequence are rejected here — they should be loaded as GenAI.
     */
    private suspend fun generateNoKv(
        prompt: PromptProcessor.PromptParts,
        options: GenerationOptions,
    ): Flow<Token> = callbackFlow {
        val s = session ?: throw IllegalStateException("Model not loaded")
        val tok = tokenizer ?: throw IllegalStateException("No tokenizer for generation")
        val e = env ?: throw IllegalStateException("Runtime not initialized")

        // The raw ORT Java API cannot construct ONNX Sequence tensors, so a KV-cache
        // (`past_key_values`) export cannot be driven. Reject it with a clear message;
        // this runtime supports no-KV exports via a full-context autoregressive loop.
        val kvInput = s.inputNames.firstOrNull { it.startsWith("past_key_values") }
        if (kvInput != null) {
            throw UnsupportedOperationException(
                "This ONNX export requires the 'past_key_values' KV-cache sequence input, " +
                    "which the raw ONNX Runtime Java API cannot construct. Use a no-KV export " +
                    "or a KV-cache-capable runtime (e.g. ONNX Runtime GenAI).",
            )
        }

        val logitsName = s.outputNames.firstOrNull { it == "logits" } ?: s.outputNames.first()
        val inputIdsName = s.inputNames.firstOrNull { it.contains("input_ids") }
            ?: throw UnsupportedOperationException("No 'input_ids' input found in the ONNX model")
        val attentionName = s.inputNames.firstOrNull { it.contains("attention_mask") }
        val positionName = s.inputNames.firstOrNull { it.contains("position_ids") }
        val rng = java.util.Random(options.seed.toLong())
        stopRequested = false

        withContext(Dispatchers.Default) {
            val promptIds = tok.encode(prompt.render(), addSpecialTokens = false).map { it.toLong() }
            val allIds = mutableListOf<Long>()
            allIds += promptIds
            var index = 0L
            val eosId = eosTokenId

            // Vision: decode the first image attachment into a pixel_values tensor once.
            val visionTensor: OnnxTensor? = run {
                val att = prompt.attachments.firstOrNull()
                val vName = visionInputName
                when {
                    att == null -> null
                    vName == null -> {
                        config.log.log(LogProvider.Level.WARN, TAG, "Image attachment ignored: model has no vision input")
                        null
                    }
                    else -> runCatching {
                        val floats = ImagePreprocessor().prepare(
                            att.bytes, visionWidth, visionHeight,
                            ImagePreprocessor.IMAGENET_MEAN, ImagePreprocessor.IMAGENET_STD,
                        )
                        OnnxTensor.createTensor(
                            e, java.nio.FloatBuffer.wrap(floats),
                            longArrayOf(1L, 3L, visionHeight.toLong(), visionWidth.toLong()),
                        )
                    }.onFailure {
                        config.log.log(LogProvider.Level.WARN, TAG, "Image preprocessing failed: ${it.message}")
                        throw it
                    }.getOrThrow()
                }
            }

            try {
                for (step in 0 until options.maxTokens) {
                    if (stopRequested) break
                    val seqLen = allIds.size
                    val idsTensor = OnnxTensor.createTensor(e, arrayOf(allIds.toLongArray()))
                    val attnTensor = if (attentionName != null) {
                        OnnxTensor.createTensor(e, arrayOf(LongArray(seqLen) { 1L }))
                    } else null
                    val posTensor = if (positionName != null) {
                        OnnxTensor.createTensor(e, arrayOf(LongArray(seqLen) { it.toLong() }))
                    } else null

                    val feeds = HashMap<String, OnnxTensor>()
                    feeds[inputIdsName] = idsTensor
                    if (attentionName != null && attnTensor != null) feeds[attentionName] = attnTensor
                    if (positionName != null && posTensor != null) feeds[positionName] = posTensor
                    val vName = visionInputName
                    if (vName != null && visionTensor != null) {
                        feeds[vName] = visionTensor
                    }

                    val outputs = try {
                        s.run(feeds)
                    } finally {
                        idsTensor.close()
                        attnTensor?.close()
                        posTensor?.close()
                    }

                    try {
                        val logitsTensor = outputs.get(logitsName).get() as OnnxTensor
                        val buf = logitsTensor.floatBuffer
                        val vocab = buf.remaining() / seqLen
                        val last = FloatArray(vocab)
                        buf.position((seqLen - 1) * vocab)
                        buf.get(last)

                        val tokenId = Sampler.sample(last, options, rng)
                        if (tokenId == eosId) break
                        val piece = tok.decode(listOf(tokenId))
                        if (piece.isNotEmpty()) {
                            trySend(Token(index = index++, id = tokenId.toLong(), text = piece))
                        }
                        allIds += tokenId.toLong()
                    } finally {
                        outputs.close()
                    }
                }
            } finally {
                visionTensor?.close()
                stopRequested = false
            }
        }
        close()
    }

    override suspend fun tokenize(handle: ModelHandle, text: String): List<Int> =
        tokenizer?.encode(text)?.toList() ?: emptyList()

    override suspend fun embeddings(handle: ModelHandle, text: String): FloatArray {
        val s = session ?: throw IllegalStateException("Model not loaded")
        val tok = tokenizer ?: throw IllegalStateException("No tokenizer for embeddings")
        val e = env ?: throw IllegalStateException("Runtime not initialized")

        return withContext(Dispatchers.Default) {
            val ids = tok.encode(text).map { it.toLong() }.toLongArray()
            val seqLen = ids.size
            val shape = longArrayOf(1L, seqLen.toLong())
            val attention = LongArray(seqLen) { 1L }
            val tokenType = LongArray(seqLen) { 0L }

            val inputs = s.inputNames
            val feeds = HashMap<String, OnnxTensor>()
            // Generic (Object) overload derives shape from array nesting: [1, seq] requires long[][].
            val idTensor = OnnxTensor.createTensor(e, arrayOf(ids))
            val maskTensor = OnnxTensor.createTensor(e, arrayOf(attention))
            val typeTensor = OnnxTensor.createTensor(e, arrayOf(tokenType))
            for (name in inputs) {
                feeds[name] = when {
                    name.contains("attention_mask") -> maskTensor
                    name.contains("token_type") -> typeTensor
                    else -> idTensor
                }
            }

            val outputs = try {
                s.run(feeds)
            } finally {
                idTensor.close(); maskTensor.close(); typeTensor.close()
            }

            val out = outputs.get(s.outputNames.first())
            val buf: FloatBuffer = (out.get() as OnnxTensor).getFloatBuffer()
            val total = buf.remaining()
            val dim = total / seqLen
            embeddingDim = dim
            val flat = FloatArray(total)
            buf.get(flat)
            outputs.close()

            meanPool(flat, seqLen, dim, attention).also { l2Normalize(it) }
        }
    }

    private fun meanPool(flat: FloatArray, seqLen: Int, dim: Int, attention: LongArray): FloatArray {
        val pooled = FloatArray(dim)
        var count = 0
        for (i in 0 until seqLen) {
            if (attention[i] == 1L) {
                count++
                for (d in 0 until dim) pooled[d] += flat[i * dim + d]
            }
        }
        if (count > 0) for (d in 0 until dim) pooled[d] /= count
        return pooled
    }

    private fun l2Normalize(v: FloatArray) {
        var norm = 0f
        for (x in v) norm += x * x
        norm = kotlin.math.sqrt(norm)
        if (norm > 0f) for (i in v.indices) v[i] /= norm
    }

    override suspend fun stop(handle: ModelHandle) {
        stopRequested = true
        genAi?.stop()
    }

    /**
     * Start a fresh KV cache for a GenAI chat session (used by `resetChat()`). No-op for raw-ORT
     * models, whose generation is already stateless.
     */
    override fun resetSession(handle: ModelHandle) {
        genAi?.resetSession()
    }

    /**
     * Chat template for the loaded model, read from metadata — mirrors
     * `DefaultPromptProcessor.templateFor` so GenAI turn deltas match the SDK's prompt rendering.
     */
    private val template: PromptProcessor.Template
        get() = when (currentModelMetadata?.get("template")?.lowercase()) {
            "qwen" -> PromptProcessor.Template.QWEN
            "llama", "llama3" -> PromptProcessor.Template.LLAMA
            "raw" -> PromptProcessor.Template.RAW
            else -> PromptProcessor.Template.CHATML
        }

    private var currentModelMetadata: Map<String, String>? = null

    companion object {
        private const val TAG = "EdgeDroid.OnnxRuntime"
    }
}

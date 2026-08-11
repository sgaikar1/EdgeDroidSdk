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
            1L
        }
        _state.value = RuntimeState.ModelLoaded
        return handle
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

    override suspend fun unload(handle: ModelHandle) {
        withContext(Dispatchers.Default) {
            session?.close()
            session = null
            tokenizer = null
        }
        _state.value = RuntimeState.Initialized
    }

    override suspend fun generate(
        handle: ModelHandle,
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
    }

    companion object {
        private const val TAG = "EdgeDroid.OnnxRuntime"
    }
}

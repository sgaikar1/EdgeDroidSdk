package com.sgaikar1.edgedroid.runtime.llama

/**
 * Thin JNI surface. Native pointers never cross this boundary — they stay inside the C++ side
 * as opaque [Long] handles. No other SDK module knows this class exists.
 */
internal object NativeLlama {

    init {
        System.loadLibrary("edgedroid_llama")
    }

    fun interface TokenCallback {
        fun onToken(text: String)
    }

    external fun nativeInit()

    /** Number of GPU (Vulkan) devices the backend enumerated; 0 = CPU only. */
    external fun nativeGpuDeviceCount(): Int

    external fun nativeLoadModel(
        path: String,
        nCtx: Int,
        nThreads: Int,
        nThreadsBatch: Int,
        nBatch: Int,
        nGpuLayers: Int,
        mmap: Boolean,
    ): Long

    external fun nativeTokenize(handle: Long, text: String): IntArray

/**
 * Decodes [prefix] into the session's KV cache (positions 0..P-1) and caches it. No-op when
 * the prefix tokens are unchanged from the previous call. Returns true on success.
 */
external fun nativeSetPrefix(handle: Long, prefix: String): Boolean

/**
 * Generates a completion for [body] after the cached prefix. Returns the llama.cpp perf
 * counters for this call as `[t_p_eval_ms, t_eval_ms, n_p_eval, n_eval]` (prompt time,
 * decode time, prompt tokens, generated tokens), or `null` when timings are unavailable.
 */
external fun nativeGenerate(
        handle: Long,
        body: String,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        maxTokens: Int,
        repeatPenalty: Float,
        seed: Int,
        callback: TokenCallback,
    ): DoubleArray?

    external fun nativeStop(handle: Long)

    external fun nativeUnload(handle: Long)

    /** Load the mmproj vision encoder (image->embeddings) for a vision-capable model. */
    external fun nativeLoadVisionModel(handle: Long, mmprojPath: String, nThreads: Int): Boolean

    /**
     * Image->text generation; the prompt text must contain the `<image>` marker. Returns the
     * same llama.cpp perf array as [nativeGenerate].
     */
    external fun nativeGenerateVision(
        handle: Long,
        prompt: String,
        imageBytes: ByteArray,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        maxTokens: Int,
        repeatPenalty: Float,
        seed: Int,
        callback: TokenCallback,
    ): DoubleArray?
}

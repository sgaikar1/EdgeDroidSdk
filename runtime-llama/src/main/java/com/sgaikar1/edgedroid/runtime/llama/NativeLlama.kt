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
    )

    external fun nativeStop(handle: Long)

    external fun nativeUnload(handle: Long)
}

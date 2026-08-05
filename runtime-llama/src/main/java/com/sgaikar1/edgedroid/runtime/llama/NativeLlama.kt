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

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
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

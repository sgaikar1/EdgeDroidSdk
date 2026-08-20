package com.sgaikar1.edgedroid.runtime.whisper

/**
 * Thin JNI surface. Native pointers never cross this boundary — they stay inside the C++ side
 * as opaque [Long] handles. No other SDK module knows this class exists.
 */
internal object NativeWhisper {

    init {
        System.loadLibrary("edgedroid_whisper")
    }

    fun interface SegmentCallback {
        fun onSegment(startMs: Long, endMs: Long, text: String)
    }

    external fun nativeInit()

    external fun nativeLoadModel(path: String, nThreads: Int): Long

    /**
     * Runs a full transcription pass over [pcm] (16-bit little-endian PCM). When [callback]
     * is non-null, segments are delivered as they are finalized (partial/streaming results).
     * Returns true on success.
     */
    external fun nativeTranscribe(
        handle: Long,
        pcm: ByteArray,
        sampleRate: Int,
        language: String?,
        nThreads: Int,
        temperature: Float,
        initialPrompt: String?,
        maxSegmentChars: Int,
        singleSegment: Boolean,
        translate: Boolean,
        callback: SegmentCallback?,
    ): Boolean

    /** Detected (or used) ISO-639-1 language code after a transcription pass. */
    external fun nativeLanguage(handle: Long): String?

    external fun nativeStop(handle: Long)

    external fun nativeUnload(handle: Long)
}
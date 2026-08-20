package com.sgaikar1.edgedroid.common

/**
 * Optional speech-synthesis capability implemented by a
 * [com.sgaikar1.edgedroid.core.Runtime] whose plugin declares
 * [com.sgaikar1.edgedroid.core.Capability.AUDIO].
 *
 * The SDK downcasts a loaded runtime to this interface when the app calls
 * [com.sgaikar1.edgedroid.api.EdgeDroid.speak]. Runtimes that do not support synthesis (e.g. the
 * chat/embedding runtimes) simply never implement it.
 */
interface TtsSpeech {

    /** The model's native sample rate in Hz (24_000 for Kokoro-82M). */
    val sampleRate: Int

    /**
     * Synthesize [text] in [voice] at [speed] and return raw float samples at [sampleRate].
     *
     * @param text plain text to speak.
     * @param voice voice id (e.g. `"af_heart"`); defaults to [DEFAULT_VOICE].
     * @param speed playback speed multiplier (1.0 = normal).
     */
    suspend fun synthesize(
        text: String,
        voice: String = DEFAULT_VOICE,
        speed: Float = 1f,
    ): TtsAudio

    companion object {
        /** The default voice used when no [voice] is requested. */
        const val DEFAULT_VOICE = "af_heart"
    }
}

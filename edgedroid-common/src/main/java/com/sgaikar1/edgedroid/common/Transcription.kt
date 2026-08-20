package com.sgaikar1.edgedroid.common

/**
 * A single transcribed speech segment with its time bounds (milliseconds since the start
 * of the audio) and the recognized text.
 */
data class TranscriptionSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * The result of a transcription pass: the full joined [text] plus timestamped [segments]
 * and the [language] used (ISO-639-1 code, or null when the runtime could not determine it).
 */
data class TranscriptionResult(
    val text: String,
    val segments: List<TranscriptionSegment>,
    val language: String? = null,
)

/**
 * Tunable knobs for a transcription pass. [language] accepts an ISO-639-1 code
 * (e.g. "en", "de") or null / "auto" for automatic language detection.
 */
data class TranscriptionOptions(
    val language: String? = null,
    val threads: Int = 4,
    val temperature: Float = 0.0f,
    val initialPrompt: String? = null,
    /** Max characters per segment; 0 = runtime default (no explicit limit). */
    val maxSegmentChars: Int = 0,
    /** Force a single segment for the whole audio (useful for short utterances). */
    val singleSegment: Boolean = false,
    /** Translate the audio into [language] instead of transcribing it in place. */
    val translate: Boolean = false,
) {
    companion object {
        val DEFAULT = TranscriptionOptions()
    }
}
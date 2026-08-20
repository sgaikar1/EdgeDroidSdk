package com.sgaikar1.edgedroid.common

/**
 * Synthesized speech: raw PCM samples as floats in roughly [-1, 1] at [sampleRate].
 *
 * The sample rate is the TTS model's native rate (24 kHz for Kokoro-82M) — it is *not*
 * resampled to the Android audio stack's 44.1 kHz. Play it back with
 * `AudioTrack(stream, sampleRate, CHANNEL_OUT_MONO, ENCODING_PCM_FLOAT, ...)` or convert to
 * a standard 16-bit PCM WAV via [toWav].
 *
 * @property samples raw mono float samples, one per frame.
 * @property sampleRate samples-per-second of [samples].
 */
data class TtsAudio(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    /** Approximate duration in milliseconds. */
    val durationMillis: Int
        get() = if (sampleRate > 0) (samples.size * 1000L / sampleRate).toInt() else 0

    /**
     * Encode [samples] into a self-contained 16-bit PCM mono WAV file (RIFF) that any media
     * player / `MediaPlayer` / `AudioTrack` can consume.
     */
    fun toWav(): ByteArray = WavPcm.encode16BitMono(samples, sampleRate)

    override fun equals(other: Any?): Boolean =
        other is TtsAudio && sampleRate == other.sampleRate && samples.contentEquals(other.samples)

    override fun hashCode(): Int = 31 * sampleRate + samples.contentHashCode()
}

/** Minimal RIFF/WAVE (PCM) writer used by [TtsAudio.toWav]. */
internal object WavPcm {

    /** Encode mono 16-bit little-endian PCM at [sampleRate] into a complete WAV file. */
    fun encode16BitMono(samples: FloatArray, sampleRate: Int): ByteArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val dataSize = samples.size * 2
        val out = java.io.ByteArrayOutputStream(44 + dataSize)

        fun ascii(s: String) = s.map(Char::code).forEach(out::write)

        // RIFF header
        ascii("RIFF")
        writeInt32(out, 36 + dataSize)
        ascii("WAVE")

        // fmt chunk
        ascii("fmt ")
        writeInt32(out, 16)
        writeInt16(out, 1)      // audioFormat = PCM
        writeInt16(out, 1)      // numChannels = mono
        writeInt32(out, sampleRate)
        writeInt32(out, sampleRate * 2) // byteRate
        writeInt16(out, 2)      // blockAlign
        writeInt16(out, 16)     // bitsPerSample

        // data chunk
        ascii("data")
        writeInt32(out, dataSize)
        for (s in samples) {
            val clamped = s.coerceIn(-1f, 1f)
            val v = (clamped * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            writeInt16(out, v)
        }
        return out.toByteArray()
    }

    private fun writeInt16(out: java.io.OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun writeInt32(out: java.io.OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }
}

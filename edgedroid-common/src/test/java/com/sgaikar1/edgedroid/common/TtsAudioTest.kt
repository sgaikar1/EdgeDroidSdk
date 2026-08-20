package com.sgaikar1.edgedroid.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsAudioTest {

    @Test
    fun durationMillis_derivedFromSampleCount() {
        val audio = TtsAudio(FloatArray(24_000), 24_000)
        assertEquals(1000, audio.durationMillis)
    }

    @Test
    fun wav_header_isValidRiffPcm() {
        val samples = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f)
        val wav = TtsAudio(samples, 24_000).toWav()

        // RIFF header
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))

        val dataSize = readInt32(wav, 40)
        assertEquals(samples.size * 2, dataSize)
        assertEquals(44 + dataSize, wav.size)

        // fmt fields
        assertEquals(1, readInt16(wav, 20))     // PCM
        assertEquals(1, readInt16(wav, 22))     // mono
        assertEquals(24_000, readInt32(wav, 24)) // sample rate
        assertEquals(24_000 * 2, readInt32(wav, 28)) // byte rate
        assertEquals(16, readInt16(wav, 34))    // bits per sample
    }

    @Test
    fun wav_pcm_encodesLittleEndianInt16() {
        val wav = TtsAudio(floatArrayOf(0f), 8_000).toWav()
        // 0.0 -> 0
        assertEquals(0, readInt16(wav, 44))
        // +1.0 -> 32767
        assertEquals(32767, readInt16(TtsAudio(floatArrayOf(1f), 8_000).toWav(), 44))
        // -1.0 -> -32767 (scaled by 32767, clamped into int16 range)
        assertEquals(-32767, readInt16(TtsAudio(floatArrayOf(-1f), 8_000).toWav(), 44))
    }

    @Test
    fun equality_usesContent() {
        val a = TtsAudio(floatArrayOf(1f, 2f), 8000)
        val b = TtsAudio(floatArrayOf(1f, 2f), 8000)
        val c = TtsAudio(floatArrayOf(1f, 3f), 8000)
        assertEquals(a, b)
        assert(!(a == c))
    }

    private fun readInt16(bytes: ByteArray, offset: Int): Int {
        val lo = bytes[offset].toInt() and 0xFF
        val hi = bytes[offset + 1].toInt() and 0xFF
        return (lo or (hi shl 8)).toShort().toInt()
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int {
        var v = 0
        for (i in 0..3) v = v or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
        return v
    }
}

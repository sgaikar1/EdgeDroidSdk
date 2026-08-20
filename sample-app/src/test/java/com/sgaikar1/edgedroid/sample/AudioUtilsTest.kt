package com.sgaikar1.edgedroid.sample

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.random.Random

class AudioUtilsTest {

    @Test
    fun `wav round-trip preserves 16-bit pcm`() {
        val pcm = ByteArray(1024)
        Random(42).nextBytes(pcm)
        val file = File.createTempFile("audio_utils", ".wav")
        try {
            AudioUtils.writeWav(pcm, 16000, 1, file)
            val decoded = AudioUtils.decodeWav(file.readBytes())
            assertEquals(16000, decoded.sampleRate)
            assertEquals(1, decoded.channels)
            assertArrayEquals(pcm, decoded.pcm)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `stereo wav is mixed down to mono with same length`() {
        // Two identical channels should mix to the same sample values.
        val samples = 100
        val left = ShortArray(samples) { (it * 7).toShort() }
        val right = left.copyOf()
        val stereo = ByteArray(samples * 2 * 2)
        for (i in 0 until samples) {
            write16(stereo, i * 4, left[i])
            write16(stereo, i * 4 + 2, right[i])
        }
        val wavBytes = buildWav(stereo, 16000, 2)
        val decoded = AudioUtils.decodeWav(wavBytes)
        assertEquals(1, decoded.channels)

        val mono = ShortArray(samples)
        for (i in 0 until samples) {
            mono[i] = ((decoded.pcm[i * 2].toInt() and 0xFF) or (decoded.pcm[i * 2 + 1].toInt() shl 8)).toShort()
        }
        assertArrayEquals(left, mono)
    }

    private fun write16(dst: ByteArray, offset: Int, v: Short) {
        dst[offset] = (v.toInt() and 0xFF).toByte()
        dst[offset + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
    }

    /** Minimal 16-bit PCM WAV (two channels). */
    private fun buildWav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val file = File.createTempFile("stereo", ".wav")
        try {
            AudioUtils.writeWav(pcm, sampleRate, channels, file)
            return file.readBytes()
        } finally {
            file.delete()
        }
    }
}

package com.sgaikar1.edgedroid.runtime.tts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroVoiceTest {

    /** The bundled default voice lives in the module's assets dir (path relative to module root). */
    private val bundled: File = File("src/main/assets/voices/af_heart.bin")

    @Test
    fun bundledVoice_is510x256() {
        assertTrue("Bundled voice missing: ${bundled.absolutePath}", bundled.isFile)
        val rows = KokoroVoice.load(bundled)
        assertEquals(KokoroVoice.ROWS, rows.size)
        assertEquals(KokoroVoice.DIM, rows[0].size)
    }

    @Test
    fun styleFor_indexesByTokenCount() {
        val rows = KokoroVoice.load(bundled)
        val style = KokoroVoice.styleTensor(rows, tokenCount = 42)
        assertEquals(KokoroVoice.DIM, style.size)
        assertTrue(style[0] != Float.NaN)
    }

    @Test
    fun styleFor_clampsOutOfRangeTokenCount() {
        val rows = KokoroVoice.load(bundled)
        val low = KokoroVoice.styleTensor(rows, -5)
        val high = KokoroVoice.styleTensor(rows, 10_000)
        assertEquals(KokoroVoice.DIM, low.size)
        assertEquals(KokoroVoice.DIM, high.size)
        assertTrue(low.contentEquals(rows[0]))
        assertTrue(high.contentEquals(rows[KokoroVoice.ROWS - 1]))
    }
}

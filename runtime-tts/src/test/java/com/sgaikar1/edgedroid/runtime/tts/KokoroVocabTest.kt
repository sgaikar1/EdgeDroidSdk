package com.sgaikar1.edgedroid.runtime.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroVocabTest {

    @Test
    fun vocab_containsKnownKokoroPhonemes() {
        // A representative sample of misaki-US symbols Kokoro must accept.
        val sample = "ˈˌAIOWQYSaiɪɛæɑɔoʊuəʌɚɜbdfɡhklmnpɹstvwzʃʒʧʤθðŋ .,!?"
        assertTrue(KokoroVocab.isCovered(sample))
    }

    @Test
    fun toIds_mapsPhonemeStringToTokenIds() {
        // 'L' not in vocab; space=16; 'ˈ'=156; 'a'=43; 'I'=25; 'f'=48; 'z'=68
        val ids = KokoroVocab.toIds("ˈaIf")
        assertEquals(4, ids.size)
        assertTrue(ids.contains(156))
        assertTrue(ids.contains(43))
        assertTrue(ids.contains(25))
        assertTrue(ids.contains(48))
    }

    @Test
    fun toIds_dropsUnknownChars() {
        // 'L' and 'E' (uppercase) are not in the Kokoro vocab; 'a' is.
        val ids = KokoroVocab.toIds("LaE")
        assertEquals(1, ids.size)
        assertEquals(KokoroVocab.TOKEN_TO_ID['a'], ids[0])
    }
}

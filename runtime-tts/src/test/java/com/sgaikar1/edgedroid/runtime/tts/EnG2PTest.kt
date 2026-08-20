package com.sgaikar1.edgedroid.runtime.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnG2PTest {

    @Test
    fun phonemize_producesValidKokoroPhonemesForCommonText() {
        val sentences = listOf(
            "Life is like a box of chocolates.",
            "Hello world, how are you today?",
            "The quick brown fox jumps over the lazy dog.",
            "I can speak text to speech.",
            "Please call me at five in the morning.",
            "123 dollars and 45 cents",
        )
        for (s in sentences) {
            val phonemes = EnG2P.phonemize(s)
            assertFalse("Empty phonemes for: $s", phonemes.isBlank())
            assertTrue(
                "Phonemes not fully covered by Kokoro vocab for '$s': $phonemes",
                KokoroVocab.isCovered(phonemes),
            )
        }
    }

    @Test
    fun phonemize_knownWordsUseLexicon() {
        assertEquals("ðə", EnG2P.phonemize("the"))
        assertEquals("hɛlO", EnG2P.phonemize("hello"))
        assertEquals("wɚld", EnG2P.phonemize("world"))
    }

    @Test
    fun phonemize_numbersBecomeWords() {
        val phonemes = EnG2P.phonemize("I have 3 apples")
        assertTrue(KokoroVocab.isCovered(phonemes))
        assertTrue(phonemes.contains("θɹi")) // "three"
    }

    @Test
    fun applyStress_addsMarksToMultiSyllable() {
        assertTrue(EnG2P.applyStress("kæt") == "kæt") // single syllable, no stress
        val stressed = EnG2P.applyStress("ɛlɪʤənt")
        assertTrue(stressed.contains('ˈ'))
    }

    @Test
    fun wordToPhonemes_rulesProduceSpeakablePhonemes() {
        for (word in listOf("conversation", "computer", "together", "beautiful", "important", "telephone")) {
            val ps = EnG2P.wordToPhonemes(word)
            assertFalse("Empty for $word", ps.isEmpty())
            assertTrue("Invalid phonemes for $word: $ps", KokoroVocab.isCovered(ps))
        }
    }
}

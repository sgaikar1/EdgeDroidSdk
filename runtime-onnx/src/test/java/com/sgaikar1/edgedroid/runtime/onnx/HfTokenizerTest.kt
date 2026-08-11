package com.sgaikar1.edgedroid.runtime.onnx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HfTokenizerTest {

    private val wordPieceJson = """
        {
          "model": {
            "type": "WordPiece",
            "vocab": {
              "[UNK]": 0, "[CLS]": 101, "[SEP]": 102,
              "hello": 7592, "world": 2088, "##ing": 2003, "test": 1290
            },
            "unk_token": "[UNK]",
            "continuing_subword_prefix": "##"
          },
          "added_tokens": [
            {"id": 101, "content": "[CLS]", "special": true},
            {"id": 102, "content": "[SEP]", "special": true},
            {"id": 0, "content": "[UNK]", "special": true}
          ],
          "post_processor": {"type": "TemplateProcessing"}
        }
    """.trimIndent()

    @Test
    fun `wordpiece encodes known words with cls sep specials`() {
        val tok = HfTokenizer.fromJson(wordPieceJson)
        assertArrayEquals(
            intArrayOf(101, 7592, 2088, 102),
            tok.encode("hello world"),
        )
    }

    @Test
    fun `wordpiece splits unknown subwords with prefix`() {
        val tok = HfTokenizer.fromJson(wordPieceJson)
        // "testing" -> "test" + "##ing"
        assertArrayEquals(intArrayOf(101, 1290, 2003, 102), tok.encode("testing"))
    }

    @Test
    fun `wordpiece unknown word maps to unk`() {
        val tok = HfTokenizer.fromJson(wordPieceJson)
        val ids = tok.encode("zzzzzz")
        assertTrue(ids.contains(0))
    }

    @Test
    fun `special tokens can be disabled`() {
        val tok = HfTokenizer.fromJson(wordPieceJson)
        assertArrayEquals(intArrayOf(7592), tok.encode("hello", addSpecialTokens = false))
    }

    @Test
    fun `real all-minilm tokenizer encodes the reference sentence`() {
        val json = javaClass.getResourceAsStream("/all-minilm-tokenizer.json")!!
            .bufferedReader().readText()
        val tok = HfTokenizer.fromJson(json)
        // Standard BERT/MiniLM reference: [CLS] This is a test [SEP]
        assertArrayEquals(
            intArrayOf(101, 2023, 2003, 1037, 3231, 102),
            tok.encode("This is a test"),
        )
    }

    @Test
    fun `wordpiece decode reconstructs text`() {
        val tok = HfTokenizer.fromJson(wordPieceJson)
        assertEquals("hello world", tok.decode(listOf(7592, 2088)))
        assertEquals("testing", tok.decode(listOf(1290, 2003)))
    }

    @Test
    fun `real tokenizer round-trips encode decode`() {
        val json = javaClass.getResourceAsStream("/all-minilm-tokenizer.json")!!
            .bufferedReader().readText()
        val tok = HfTokenizer.fromJson(json)
        val ids = tok.encode("The quick brown fox")
        val text = tok.decode(ids.toList())
        assertEquals("the quick brown fox", text)
    }
}

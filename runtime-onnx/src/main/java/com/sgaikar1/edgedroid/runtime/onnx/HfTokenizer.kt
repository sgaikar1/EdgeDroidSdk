package com.sgaikar1.edgedroid.runtime.onnx

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Minimal Hugging Face `tokenizer.json` parser supporting the two common model families:
 *
 *  - **WordPiece** (BERT-style, e.g. all-MiniLM-L6-v2) — vocab + `##` subword prefix,
 *    `[CLS]`/`[SEP]` specials for single-segment inputs.
 *  - **ByteLevel BPE** (GPT-2/Llama/Qwen-style) — vocab + merges + GPT-2 byte encoder.
 *
 * This is a pragmatic subset of the HF tokenizer spec, sufficient for embedding/LLM models
 * exported in the standard convention. Not a byte-exact port of `tokenizers`.
 */
internal class HfTokenizer private constructor(
    private val modelType: String,
    private val vocab: Map<String, Int>,
    private val merges: List<Pair<String, String>>,
    private val unkToken: String,
    private val continuingSubwordPrefix: String,
    private val specialTokenIds: Map<String, Int>,
    private val addsClsSep: Boolean,
) {

    fun encode(text: String, addSpecialTokens: Boolean = true): IntArray {
        val pieces = if (modelType.equals("BPE", ignoreCase = true)) {
            bpeEncode(text)
        } else {
            wordPieceEncode(text)
        }
        val list = mutableListOf<Int>()
        if (addSpecialTokens && addsClsSep) {
            specialTokenIds["[CLS]"]?.let { list += it }
        }
        list += pieces
        if (addSpecialTokens && addsClsSep) {
            specialTokenIds["[SEP]"]?.let { list += it }
        }
        return list.toIntArray()
    }

    // ---- WordPiece ----

    private fun wordPieceEncode(text: String): List<Int> {
        val result = mutableListOf<Int>()
        for (rawWord in preTokenize(text)) {
            val word = rawWord.lowercase()
            if (word.isEmpty()) continue
            if (word in vocab) {
                result += vocab.getValue(word)
                continue
            }
            // Split into subwords with the continuing prefix.
            val tokens = mutableListOf<String>()
            var start = 0
            var isSubword = false
            while (start < word.length) {
                var end = word.length
                var found = false
                var currentSubword: String? = null
                while (start < end) {
                    val candidate = (if (isSubword) continuingSubwordPrefix else "") + word.substring(start, end)
                    if (candidate in vocab) {
                        currentSubword = candidate
                        found = true
                        break
                    }
                    end -= 1
                }
                if (!found) {
                    tokens.add(unkToken)
                    break
                }
                tokens.add(currentSubword!!)
                start = end
                isSubword = true
            }
            result += tokens.map { vocab[it] ?: vocab[unkToken] ?: 0 }
        }
        return result
    }

    // ---- ByteLevel BPE ----

    private fun bpeEncode(text: String): List<Int> {
        val byteEncoded = ByteLevel.encode(text)
        val result = mutableListOf<Int>()
        for (word in byteEncoded.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val symbols = word.map { it.toString() }.toMutableList()
            while (true) {
                var bestRank = Int.MAX_VALUE
                var bestIdx = -1
                for (i in 0 until symbols.size - 1) {
                    val rank = mergeRank(symbols[i] to symbols[i + 1])
                    if (rank >= 0 && rank < bestRank) {
                        bestRank = rank
                        bestIdx = i
                    }
                }
                if (bestIdx < 0) break
                symbols[bestIdx] = symbols[bestIdx] + symbols[bestIdx + 1]
                symbols.removeAt(bestIdx + 1)
            }
            result += symbols.map { vocab[it] ?: vocab[unkToken] ?: 0 }
        }
        return result
    }

    private fun mergeRank(pair: Pair<String, String>): Int = merges.indexOf(pair)

    // ---- shared ----

    /** Rough pre-tokenizer: split on whitespace and punctuation, keeping case for WordPiece. */
    private fun preTokenize(text: String): List<String> {
        return text.split(Regex("([^\\p{L}\\p{N}]+)")).filter { it.isNotEmpty() }
    }

    private object ByteLevel {
        private val byteToChar: Map<Int, Char> = run {
            val map = mutableMapOf<Int, Char>()
            var n = 0
            for (b in 0..255) {
                val printable = (b in 0x21..0x7E) || (b in 0xA1..0xAC) || (b in 0xAE..0xFF)
                map[b] = if (printable) b.toChar() else (256 + n++).toChar()
            }
            map
        }

        fun encode(text: String): String =
            text.encodeToByteArray().joinToString("") { byteToChar.getValue(it.toInt() and 0xFF).toString() }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): HfTokenizer {
            val root = json.decodeFromString<TokenizerJson>(text)
            val model = root.model
            val specials = root.addedTokens
                .filter { it.special || it.content in model.vocab }
                .associate { it.content to it.id }
            val clsSep = root.postProcessor?.get("type")?.let {
                it.toString().contains("Template", ignoreCase = true) ||
                    it.toString().contains("Bert", ignoreCase = true)
            } ?: false
            val mergePairs = model.merges.mapNotNull {
                val parts = it.trim().split(Regex("\\s+"))
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            return HfTokenizer(
                modelType = model.type,
                vocab = model.vocab,
                merges = mergePairs,
                unkToken = model.unkToken,
                continuingSubwordPrefix = model.continuingSubwordPrefix,
                specialTokenIds = specials,
                addsClsSep = clsSep,
            )
        }
    }
}

@Serializable
internal data class TokenizerJson(
    val model: ModelJson,
    @SerialName("added_tokens") val addedTokens: List<AddedTokenJson> = emptyList(),
    @SerialName("post_processor") val postProcessor: JsonObject? = null,
)

@Serializable
internal data class ModelJson(
    val type: String,
    val vocab: Map<String, Int> = emptyMap(),
    val merges: List<String> = emptyList(),
    @SerialName("unk_token") val unkToken: String = "[UNK]",
    @SerialName("continuing_subword_prefix") val continuingSubwordPrefix: String = "##",
)

@Serializable
internal data class AddedTokenJson(
    val id: Int,
    val content: String,
    val special: Boolean = false,
)

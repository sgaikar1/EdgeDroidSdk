package com.sgaikar1.edgedroid.runtime.tts

/**
 * Compact English grapheme-to-phoneme (G2P) engine.
 *
 * Kokoro-82M is phoneme-based: it does not consume raw text. Text must first be converted to a
 * misaki-compatible phoneme string (see `hexgrad/misaki`), and every phoneme character is then
 * mapped to a Kokoro token id via [KokoroVocab].
 *
 * This is a lightweight, dependency-free port of that pipeline: a small pronunciation lexicon
 * for the most common / irregular words plus a letter-to-sound rule engine for everything else.
 * It is intentionally simpler than `misaki` (which uses spaCy POS tagging, a large CMU-derived
 * lexicon and a neural fallback) — quality is basic but the output is always a *valid* Kokoro
 * phoneme string. A production port of `misaki` is tracked as future work.
 *
 * The phoneme alphabet matches misaki US (and therefore the Kokoro vocab): vowels `A I O Q W Y`
 * for diphthongs, `i ɪ e ɛ æ ɑ ɔ o ʊ u ə ʌ ɚ ɜ`, consonants `p b t d k ɡ f v θ ð s z ʃ ʒ ʧ ʤ h
 * m n ŋ l ɹ w j`, and stress marks `ˈ ˌ`.
 */
object EnG2P {

    private const val SPACE = " "

    private val VOWELS = setOf(
        'i', 'ɪ', 'e', 'ɛ', 'æ', 'ɑ', 'ɔ', 'o', 'ʊ', 'u', 'ə', 'ʌ', 'ɚ', 'ɜ',
        'A', 'I', 'O', 'Q', 'W', 'Y', 'S',
    )

    private fun isVowel(c: Char) = c in VOWELS

    /** Convert a run of text to a phoneme string (words separated by spaces, punctuation kept). */
    fun phonemize(text: String): String {
        val normalized = normalize(text)
        val out = StringBuilder()
        for (token in tokenize(normalized)) {
            if (token.first().isLetter()) {
                out.append(wordToPhonemes(token.lowercase()))
            } else {
                out.append(symbolToPhoneme(token))
            }
            out.append(SPACE)
        }
        return out.toString().trimEnd()
    }

    // ------------------------------------------------------------------ tokenize/normalize

    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
        }
        for (c in text) {
            if (c.isLetterOrDigit()) {
                current.append(c)
            } else {
                flush()
                if (c.isWhitespace()) continue
                // punctuation token
                tokens += c.toString()
            }
        }
        flush()
        return tokens
    }

    /** Expand numbers, currency and common symbols into words so the G2P can speak them. */
    private fun normalize(text: String): String {
        var s = text
        s = s.replace('%', ' ') // handled below
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == ',' || s[i] == '.')) i++
                    val num = s.substring(start, i)
                    sb.append(numToWords(num))
                }
                c == '$' -> { sb.append("dollar "); i++ }
                c == '£' -> { sb.append("pound "); i++ }
                c == '€' -> { sb.append("euro "); i++ }
                c == '%' -> { sb.append(" percent "); i++ }
                c == '&' -> { sb.append(" and "); i++ }
                c == '+' -> { sb.append(" plus "); i++ }
                c == '@' -> { sb.append(" at "); i++ }
                c == '/' -> { sb.append(' '); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun numToWords(num: String): String {
        val cleaned = num.replace(",", "")
        if ('.' in cleaned) {
            val parts = cleaned.split('.')
            val whole = if (parts[0].isEmpty()) "zero" else intToWords(parts[0].toLong())
            val frac = parts[1].map { digitToWord(it) }.joinToString(" ")
            return "$whole point $frac"
        }
        return try {
            intToWords(cleaned.toLong())
        } catch (e: NumberFormatException) {
            cleaned
        }
    }

    private val ONES = arrayOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private fun digitToWord(d: Char): String = ONES[d - '0']

    private fun intToWords(n: Long): String {
        if (n == 0L) return "zero"
        if (n < 0L) return "minus " + intToWords(-n)
        if (n < 20L) return ONES[n.toInt()]
        if (n < 100L) {
            val t = TENS[(n / 10).toInt()]
            return if (n % 10 == 0L) t else "$t ${ONES[(n % 10).toInt()]}"
        }
        if (n < 1000L) {
            val h = intToWords(n / 100) + " hundred"
            return if (n % 100 == 0L) h else "$h ${intToWords(n % 100)}"
        }
        for ((value, name) in listOf(1_000_000_000L to "billion", 1_000_000L to "million", 1000L to "thousand")) {
            if (n >= value) {
                val big = intToWords(n / value) + " $name"
                return if (n % value == 0L) big else "$big ${intToWords(n % value)}"
            }
        }
        return n.toString()
    }

    private fun symbolToPhoneme(token: String): String = when (token) {
        "." -> "."
        "," -> ","
        "!" -> "!"
        "?" -> "?"
        ";" -> ";"
        ":" -> ":"
        "(" -> "("
        ")" -> ")"
        "\"" -> "\""
        "“" -> "“"
        "”" -> "”"
        "—" -> "—"
        "…" -> "…"
        else -> ""
    }

    // ---------------------------------------------------------------------- lexicon

    /** Common/irregular words, phonemes written in the misaki-US alphabet. */
    private val LEXICON: Map<String, String> = mapOf(
        "the" to "ðə", "a" to "ə", "an" to "ən", "and" to "ænd", "of" to "əv",
        "to" to "tə", "for" to "fɔɹ", "with" to "wɪð", "you" to "ju", "your" to "jɔɹ",
        "yours" to "jɔɹz", "i" to "I", "me" to "mi", "my" to "mI", "mine" to "mIn",
        "we" to "wi", "us" to "ʌs", "our" to "Wɹ", "ours" to "Wɹz", "they" to "ðA",
        "them" to "ðɛm", "their" to "ðɛɹ", "he" to "hi", "him" to "hɪm", "his" to "hɪz",
        "she" to "ʃi", "her" to "hɚ", "it" to "ɪt", "its" to "ɪts", "this" to "ðɪs",
        "that" to "ðæt", "these" to "ðiz", "those" to "ðOz", "there" to "ðɛɹ",
        "who" to "hu", "whom" to "hum", "whose" to "huz", "what" to "wʌt", "when" to "wɛn",
        "where" to "wɛɹ", "why" to "WI", "which" to "wɪʧ", "how" to "hW",
        "is" to "ɪz", "are" to "ɑɹ", "was" to "wʌz", "were" to "wɚ", "be" to "bi",
        "been" to "bɪn", "being" to "biɪŋ", "am" to "æm", "do" to "du", "does" to "dʌz",
        "did" to "dɪd", "done" to "dʌn", "doing" to "duɪŋ", "have" to "hæv", "has" to "hæz",
        "had" to "hæd", "having" to "hævɪŋ", "will" to "wɪl", "would" to "wʊd",
        "can" to "kæn", "could" to "kʊd", "shall" to "ʃæl", "should" to "ʃʊd",
        "may" to "mA", "might" to "mIt", "must" to "mʌst", "not" to "nɑt", "no" to "nO",
        "yes" to "jɛs", "so" to "sO", "if" to "ɪf", "but" to "bʌt", "or" to "ɔɹ",
        "because" to "bɪkʌz", "as" to "æz", "than" to "ðæn", "then" to "ðɛn",
        "now" to "nW", "just" to "ʤʌst", "like" to "lIk", "love" to "lʌv", "good" to "ɡʊd",
        "well" to "wɛl", "very" to "vɛɹi", "really" to "ɹili", "about" to "əbWt",
        "from" to "fɹʌm", "into" to "ɪntu", "out" to "Wt", "up" to "ʌp", "down" to "dWn",
        "over" to "Ovɚ", "under" to "ʌndɚ", "on" to "ɔn", "off" to "ɔf", "in" to "ɪn",
        "at" to "æt", "by" to "bI", "one" to "wʌn", "two" to "tu", "three" to "θɹi",
        "four" to "fɔɹ", "five" to "fIv", "six" to "sɪks", "seven" to "sɛvən",
        "eight" to "At", "nine" to "nIn", "ten" to "tɛn", "hundred" to "hʌndɹəd",
        "thousand" to "θWzənd", "million" to "mɪljən", "billion" to "bɪljən",
        "hello" to "hɛlO", "hi" to "hI", "hey" to "hA", "goodbye" to "ɡʊdbI",
        "thank" to "θæŋk", "thanks" to "θæŋks", "please" to "pliz", "sorry" to "sɔɹi",
        "yes" to "jɛs", "okay" to "OkA", "please" to "pliz", "today" to "tədA",
        "tomorrow" to "təmɑɹo", "yesterday" to "jɛstɚdA", "world" to "wɚld",
        "people" to "pipəl", "person" to "pɚsən", "time" to "tIm", "thing" to "θɪŋ",
        "things" to "θɪŋz", "way" to "wA", "life" to "lIf", "day" to "dA", "night" to "nIt",
        "work" to "wɚk", "home" to "hOm", "house" to "hWs", "name" to "nAm",
        "hello" to "hɛlO", "speak" to "spik", "speech" to "spiʧ", "text" to "tɛkst",
        "chat" to "ʧæt", "voice" to "vɔIs", "sound" to "sWnd", "audio" to "ɔdio",
        "listen" to "lɪsən", "say" to "sA", "said" to "sɛd", "talk" to "tɔk", "read" to "ɹid",
        "write" to "ɹIt", "learn" to "lɚn", "language" to "læŋɡwɪʤ", "music" to "mjuzɪk",
    )

    // ------------------------------------------------------------------ letter-to-sound

    /** Convert a lowercase alphabetic word to phonemes via lexicon or rules. */
    fun wordToPhonemes(word: String): String {
        if (word.isEmpty()) return ""
        LEXICON[word]?.let { return it }

        var w = word
        var suffix = ""
        for (sfx in SUFFIXES) {
            if (w.length > sfx.suffix.length + 2 && w.endsWith(sfx.suffix)) {
                suffix = sfx.suffix
                w = w.removeSuffix(sfx.suffix)
                val stem = wordToPhonemesNoLexicon(w)
                if (stem.isNotEmpty()) {
                    val joined = stem + sfx.phonemes
                    return applyStress(joined)
                }
            }
        }
        return applyStress(wordToPhonemesNoLexicon(w))
    }

    private class Suffix(val suffix: String, val phonemes: String)

    private val SUFFIXES = listOf(
        Suffix("tion", "ʃən"), Suffix("sion", "ʒən"), Suffix("ssion", "ʃən"),
        Suffix("tive", "tɪv"), Suffix("ture", "ʧɚ"), Suffix("able", "əbəl"),
        Suffix("ible", "ɪbəl"), Suffix("ing", "ɪŋ"), Suffix("ed", "d"),
        Suffix("ly", "li"), Suffix("ness", "nəs"), Suffix("ment", "mənt"),
        Suffix("ful", "fəl"), Suffix("less", "ləs"), Suffix("ous", "əs"),
        Suffix("ic", "ɪk"), Suffix("al", "əl"), Suffix("est", "əst"), Suffix("er", "ɚ"),
        Suffix("ism", "ɪzəm"), Suffix("ist", "ɪst"), Suffix("ize", "Iz"),
        Suffix("ance", "əns"), Suffix("ence", "əns"), Suffix("age", "ɪʤ"),
        Suffix("ous", "əs"), Suffix("ive", "ɪv"),
    )

    private fun wordToPhonemesNoLexicon(word: String): String {
        val sb = StringBuilder()
        var i = 0
        val n = word.length
        while (i < n) {
            val c = word[i]
            val next = if (i + 1 < n) word[i + 1] else ' '
            val next2 = if (i + 2 < n) word[i + 2] else ' '
            val next3 = if (i + 3 < n) word[i + 3] else ' '
            val prev = if (i > 0) word[i - 1] else ' '

            when (c) {
                // ---- consonants ----
                'b' -> { sb.append('b'); i++ }
                'd' -> {
                    // -dge -> ʤ
                    if (i + 2 < n && word[i + 1] == 'g' && word[i + 2] == 'e') {
                        sb.append("ʤ"); i += 3
                    } else { sb.append('d'); i++ }
                }
                'f' -> { sb.append('f'); i++ }
                'g' -> {
                    when {
                        i + 1 < n && word[i + 1] == 'n' -> { sb.append('n'); i += 2 }
                        (i + 1 < n && word[i + 1] == 'e') || (i + 1 < n && word[i + 1] == 'i') ||
                            (i + 1 < n && word[i + 1] == 'y') -> { sb.append('ʤ'); i++ }
                        else -> { sb.append('ɡ'); i++ }
                    }
                }
                'h' -> { sb.append('h'); i++ }
                'j' -> { sb.append('ʤ'); i++ }
                'k' -> {
                    if (i + 1 < n && word[i + 1] == 'n') { sb.append('n'); i += 2 }
                    else { sb.append('k'); i++ }
                }
                'l' -> { sb.append('l'); i++ }
                'm' -> { sb.append('m'); i++ }
                'n' -> {
                    if (i + 1 < n && word[i + 1] == 'g' && (i + 2 >= n || !isVowel(word[i + 2]))) {
                        sb.append('ŋ'); i += 2
                    } else if (i + 1 < n && word[i + 1] == 'k') {
                        sb.append('ŋ'); i++ // nk -> ŋk
                    } else { sb.append('n'); i++ }
                }
                'p' -> { sb.append('p'); i++ }
                'q' -> {
                    if (i + 1 < n && word[i + 1] == 'u') {
                        sb.append('k').append('w'); i += 2
                    } else { sb.append('k'); i++ }
                }
                'r' -> { sb.append('ɹ'); i++ }
                's' -> {
                    when {
                        i + 1 < n && word[i + 1] == 'h' -> {
                            if (i + 2 < n && word[i + 2] == 'i' && (i + 3 < n && word[i + 3] == 'o' || i + 3 < n && word[i + 3] == 'e')) {
                                sb.append('ʃ'); i += 2
                            } else { sb.append('ʃ'); i += 2 }
                        }
                        i + 1 < n && word[i + 1] == 's' -> { sb.append('s'); i += 2 }
                        // between vowels often z
                        i > 0 && prev in "aeiou" && i + 1 < n && next in "aeiou" -> { sb.append('z'); i++ }
                        word.endsWith("s") && i == n - 1 && i > 0 -> {
                            sb.append(if (prev in "ptkfθ") 's' else if (prev in "szʃʒʧʤ") "ɪz" else 'z'); i++
                        }
                        else -> { sb.append('s'); i++ }
                    }
                }
                't' -> {
                    when {
                        i + 1 < n && word[i + 1] == 'h' -> {
                            sb.append(if (functionWordContext(word)) 'ð' else 'θ'); i += 2
                        }
                        i + 1 < n && word[i + 1] == 'i' && (i + 2 < n && word[i + 2] == 'o') -> { sb.append('ʃ'); i += 2 }
                        i + 1 < n && word[i + 1] == 'u' && (i + 2 < n && word[i + 2] == 'r' && i + 3 < n && word[i + 3] == 'e') -> { sb.append('ʧ'); i += 2 }
                        i + 1 < n && word[i + 1] == 'c' && (i + 2 < n && word[i + 2] == 'h') -> { sb.append('ʧ'); i += 2 }
                        else -> { sb.append('t'); i++ }
                    }
                }
                'v' -> { sb.append('v'); i++ }
                'w' -> { sb.append('w'); i++ }
                'x' -> {
                    if (i + 1 < n && word[i + 1] == 'i' && (i + 2 < n && word[i + 2] == 'o' || i + 2 < n && word[i + 2] == 'e')) {
                        sb.append('k').append('ʃ'); i++
                    } else if (i > 0 && i + 1 < n && word[i + 1] == 'a' && word.endsWith("x") || i == n - 1 && i > 0) {
                        sb.append('k').append('s'); i++
                    } else {
                        sb.append('k').append('s'); i++
                    }
                }
                'y' -> {
                    // y as vowel
                    sb.append(if (i == 0) 'j' else if (i == n - 1) 'i' else 'ɪ'); i++
                }
                'z' -> { sb.append('z'); i++ }
                'c' -> {
                    when {
                        i + 1 < n && word[i + 1] == 'h' -> { sb.append('ʧ'); i += 2 }
                        i + 1 < n && (word[i + 1] == 'e' || word[i + 1] == 'i' || word[i + 1] == 'y') -> { sb.append('s'); i++ }
                        i + 1 < n && word[i + 1] == 'k' -> { sb.append('k'); i += 2 }
                        i + 1 < n && word[i + 1] == 't' && (i + 2 < n && word[i + 2] == 'i') -> { sb.append('k').append('ʃ'); i++ }
                        else -> { sb.append('k'); i++ }
                    }
                }

                // ---- vowels ----
                'a' -> {
                    when {
                        i + 1 < n && word[i + 1] == 'i' -> { sb.append('A'); i += 2 }
                        i + 1 < n && word[i + 1] == 'u' -> { sb.append('ɔ'); i += 2 }
                        i + 1 < n && word[i + 1] == 'y' -> { sb.append('A'); i += 2 }
                        i + 1 < n && word[i + 1] == 'r' -> { sb.append('ɑ'); i += 2 }
                        i + 2 < n && word[i + 1] == 'n' && word[i + 2] == 'g' -> { sb.append('A'); i += 3 }
                        // magic e / open syllable
                        (i + 1 < n && word[i + 1] == 'e') -> { sb.append('A'); i += 2 }
                        isOpenSyllable(word, i) -> { sb.append('A'); i++ }
                        else -> { sb.append('æ'); i++ }
                    }
                }
                'e' -> {
                    when {
                        i == n - 1 -> { i++ } // silent final e
                        i + 1 < n && word[i + 1] == 'a' -> { sb.append('i'); i += 2 }
                        i + 1 < n && word[i + 1] == 'i' -> { sb.append('A'); i += 2 }
                        i + 1 < n && word[i + 1] == 'e' -> { sb.append('i'); i += 2 }
                        i + 1 < n && word[i + 1] == 'r' -> { sb.append('ɚ'); i += 2 }
                        isOpenSyllable(word, i) -> { sb.append('i'); i++ }
                        else -> { sb.append('ɛ'); i++ }
                    }
                }
                'i' -> {
                    when {
                        i + 1 < n && word[i + 1] == 'e' -> { sb.append('i'); i += 2 }
                        i + 1 < n && word[i + 1] == 'a' -> { sb.append('I'); i += 2 }
                        i + 1 < n && word[i + 1] == 'o' -> { sb.append('I'); i += 2 }
                        isOpenSyllable(word, i) -> { sb.append('I'); i++ }
                        else -> { sb.append('ɪ'); i++ }
                    }
                }
                'o' -> {
                    when {
                        i + 1 < n && word[i + 1] == 'i' -> { sb.append('Q'); i += 2 }
                        i + 1 < n && word[i + 1] == 'u' -> { sb.append('W'); i += 2 }
                        i + 1 < n && word[i + 1] == 'w' -> { sb.append('O'); i += 2 }
                        i + 1 < n && word[i + 1] == 'a' -> { sb.append('O'); i += 2 }
                        i + 1 < n && word[i + 1] == 'o' -> { sb.append('u'); i += 2 }
                        i + 1 < n && word[i + 1] == 'r' -> { sb.append('ɔ'); i += 2 }
                        i + 1 < n && word[i + 1] == 'e' -> { sb.append('O'); i += 2 }
                        isOpenSyllable(word, i) -> { sb.append('O'); i++ }
                        else -> { sb.append('ɑ'); i++ }
                    }
                }
                'u' -> {
                    when {
                        i + 1 < n && word[i + 1] == 'i' -> { sb.append('u'); i += 2 }
                        i + 1 < n && word[i + 1] == 'e' -> { sb.append("ju"); i += 2 }
                        i + 1 < n && word[i + 1] == 'r' -> { sb.append('ɚ'); i += 2 }
                        i + 1 < n && word[i + 1] == 'a' -> { sb.append('W'); i += 2 }
                        i + 1 < n && word[i + 1] == 'o' -> { sb.append('u'); i += 2 }
                        isOpenSyllable(word, i) -> { sb.append("ju"); i++ }
                        else -> { sb.append('ʌ'); i++ }
                    }
                }
                '\'' -> { i++ }
                else -> { i++ }
            }
        }
        return sb.toString()
    }

    /** Rough open-syllable heuristic: vowel followed by a single consonant + vowel, or final vowel. */
    private fun isOpenSyllable(word: String, i: Int): Boolean {
        val n = word.length
        if (i == n - 1) return true
        if (i + 1 < n && !isVowelLetter(word[i + 1]) && i + 2 < n && isVowelLetter(word[i + 2])) {
            // only open if the intervening consonant is a single letter
            return true
        }
        return false
    }

    private fun isVowelLetter(c: Char) = c in "aeiouy"

    private fun functionWordContext(word: String): Boolean = word in setOf("the", "this", "that", "these", "those", "they", "them", "there", "than", "then")

    /** Add stress marks to a phoneme string based on a simple syllable heuristic. */
    fun applyStress(phonemes: String): String {
        val syllables = countSyllables(phonemes)
        if (syllables <= 1) return phonemes
        val sb = StringBuilder(phonemes)
        // place primary stress on the first vowel
        var inserted = 0
        for (i in sb.indices) {
            if (isVowel(sb[i])) {
                sb.insert(i + inserted, 'ˈ')
                inserted++
                break
            }
        }
        // secondary stress on the last vowel of long words
        if (syllables >= 4) {
            for (i in sb.length - 1 downTo 0) {
                if (isVowel(sb[i])) {
                    if (i > 0 && sb[i - 1] == 'ˈ') break
                    sb.insert(i, 'ˌ')
                    break
                }
            }
        }
        return sb.toString()
    }

    private fun countSyllables(phonemes: String): Int {
        var count = 0
        var prevVowel = false
        for (c in phonemes) {
            if (isVowel(c)) {
                if (!prevVowel) count++
                prevVowel = true
            } else {
                prevVowel = false
            }
        }
        return count
    }
}

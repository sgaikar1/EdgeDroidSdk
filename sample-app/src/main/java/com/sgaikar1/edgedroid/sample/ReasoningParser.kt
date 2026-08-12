package com.sgaikar1.edgedroid.sample

/**
 * Splits streamed model output into a reasoning block and the final answer, using the first
 * reasoning start/end markers found. Model-agnostic: Qwen-style `<|reasoning_start|>`, `think`,
 * and `<reasoning>` are supported. When no markers are present everything is the answer.
 */
object ReasoningParser {

    private val STARTS = listOf("<|reasoning_start|>", "<reasoning>", "think")
    private val ENDS = listOf("<|reasoning_end|>", "</reasoning>", "/think")

    data class Parts(val reasoning: String?, val answer: String)

    fun split(text: String): Parts {
        val startIdx = STARTS.mapNotNull { text.indexOf(it).takeIf { i -> i >= 0 } }.minOrNull()
            ?: return Parts(reasoning = null, answer = text)
        val startMarker = STARTS.first { text.indexOf(it) == startIdx }
        val afterStart = startIdx + startMarker.length

        val endIdx = ENDS.mapNotNull {
            text.indexOf(it, afterStart).takeIf { i -> i >= 0 }
        }.minOrNull()
        if (endIdx == null) {
            // Still inside the reasoning block — no answer yet.
            return Parts(reasoning = text.substring(afterStart), answer = "")
        }
        val endMarker = ENDS.first { text.indexOf(it, afterStart) == endIdx }
        return Parts(
            reasoning = text.substring(afterStart, endIdx),
            answer = text.substring(endIdx + endMarker.length),
        )
    }
}

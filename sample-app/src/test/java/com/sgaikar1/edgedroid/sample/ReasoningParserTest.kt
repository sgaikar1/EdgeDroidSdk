package com.sgaikar1.edgedroid.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningParserTest {

    @Test
    fun `no markers means everything is the answer`() {
        val p = ReasoningParser.split("Hello there")
        assertNull(p.reasoning)
        assertEquals("Hello there", p.answer)
    }

    @Test
    fun `qwen markers split reasoning and answer`() {
        val p = ReasoningParser.split(
            "<|reasoning_start|>The capital is Paris<|reasoning_end|>The capital of France is Paris.",
        )
        assertEquals("The capital is Paris", p.reasoning)
        assertEquals("The capital of France is Paris.", p.answer)
    }

    @Test
    fun `inside reasoning block has no answer yet`() {
        val p = ReasoningParser.split("<|reasoning_start|>Still thinking")
        assertEquals("Still thinking", p.reasoning)
        assertEquals("", p.answer)
    }

    @Test
    fun `think slash-think markers work`() {
        val p = ReasoningParser.split("think compute 2+2 /think The answer is 4.")
        assertEquals(" compute 2+2 ", p.reasoning)
        assertEquals(" The answer is 4.", p.answer)
    }
}

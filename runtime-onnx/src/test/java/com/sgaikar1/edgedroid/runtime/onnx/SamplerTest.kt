package com.sgaikar1.edgedroid.runtime.onnx

import com.sgaikar1.edgedroid.common.GenerationOptions
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplerTest {

    private val logits = floatArrayOf(0.1f, 5.0f, 0.3f, 2.0f, 1.0f)

    @Test
    fun `temperature zero is greedy and returns argmax`() {
        val opts = GenerationOptions(temperature = 0f)
        assertEquals(1, Sampler.sample(logits, opts, Random(1L)))
    }

    @Test
    fun `temperature one still favors the argmax over many draws`() {
        val opts = GenerationOptions(temperature = 1f, topK = 0, topP = 1f)
        var argmaxCount = 0
        repeat(200) {
            if (Sampler.sample(logits, opts, Random(it.toLong())) == 1) argmaxCount++
        }
        assertTrue("argmax should dominate, got $argmaxCount/200", argmaxCount > 150)
    }

    @Test
    fun `top-k only samples from the top k`() {
        val opts = GenerationOptions(temperature = 1f, topK = 2, topP = 1f)
        repeat(100) {
            val id = Sampler.sample(logits, opts, Random(it.toLong()))
            assertTrue("sampled $id outside top-2 {1,3}", id == 1 || id == 3)
        }
    }

    @Test
    fun `seed makes sampling deterministic`() {
        val opts = GenerationOptions(temperature = 1f)
        val a = Sampler.sample(logits, opts, Random(42L))
        val b = Sampler.sample(logits, opts, Random(42L))
        assertEquals(a, b)
    }

    @Test
    fun `top-p keeps high-probability tokens`() {
        val opts = GenerationOptions(temperature = 1f, topK = 0, topP = 0.2f)
        repeat(100) {
            val id = Sampler.sample(logits, opts, Random(it.toLong()))
            assertTrue("sampled low-prob $id", id == 1)
        }
    }
}

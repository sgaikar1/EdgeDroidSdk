package com.sgaikar1.edgedroid.runtime.onnx

import com.sgaikar1.edgedroid.common.GenerationOptions
import java.util.Random

/**
 * Autoregressive next-token sampling: temperature → top-k → top-p (nucleus) → softmax.
 * Pure Kotlin so it can be unit-tested on the JVM without ONNX Runtime.
 */
internal object Sampler {

    fun sample(logitsRow: FloatArray, options: GenerationOptions, rng: Random = Random(options.seed.toLong())): Int {
        if (logitsRow.isEmpty()) throw IllegalArgumentException("empty logits row")
        val n = logitsRow.size

        // temperature <= 0 => greedy (like the llama runtime)
        if (options.temperature <= 0f) {
            var best = 0
            for (i in 1 until n) if (logitsRow[i] > logitsRow[best]) best = i
            return best
        }

        // candidate original token ids, tracked through top-k filtering
        var candidates = (0 until n).toList()
        var logits = logitsRow.copyOf()

        // temperature
        if (options.temperature != 1f) {
            for (i in 0 until n) logits[i] /= options.temperature
        }

        // top-k: keep the top-k original ids
        if (options.topK > 0 && options.topK < n) {
            val kept = candidates.sortedByDescending { logits[it] }.take(options.topK)
            candidates = kept
            logits = FloatArray(kept.size) { logits[kept[it]] }
        }

        // top-p (nucleus) on the softmax
        val probs = softmax(logits)
        val order = probs.indices.sortedByDescending { probs[it] }
        var cum = 0f
        var cutoff = probs.size
        for (i in order.indices) {
            cum += probs[order[i]]
            if (cum >= options.topP) {
                cutoff = i + 1
                break
            }
        }
        val keptLocal = order.take(cutoff)
        val keptProbs = keptLocal.map { probs[it] }
        val sum = keptProbs.sum().let { if (it <= 0f) 1f else it }

        var r = rng.nextFloat() * sum
        for (i in keptLocal.indices) {
            r -= keptProbs[i]
            if (r <= 0f) return candidates[keptLocal[i]]
        }
        return candidates[keptLocal.last()]
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.maxOrNull() ?: 0f
        val exps = FloatArray(logits.size) { kotlin.math.exp((logits[it] - max).toDouble()).toFloat() }
        val sum = exps.sum().let { if (it <= 0f) 1f else it }
        for (i in exps.indices) exps[i] /= sum
        return exps
    }
}

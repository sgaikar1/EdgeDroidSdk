package com.sgaikar1.edgedroid.common

/**
 * Sampling and generation options passed through the SPI. Runtimes map these onto their own
 * native parameter structs; the SDK never exposes native knobs.
 */
data class GenerationOptions(
    val temperature: Float = 0.8f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
    val maxTokens: Int = 256,
    val repeatPenalty: Float = 1.1f,
    val seed: Int = -1,
    val stopSequences: List<String> = emptyList(),
) {
    companion object {
        val DEFAULT = GenerationOptions()
    }
}

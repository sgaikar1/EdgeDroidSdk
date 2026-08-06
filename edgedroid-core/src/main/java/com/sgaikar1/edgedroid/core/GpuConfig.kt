package com.sgaikar1.edgedroid.core

/**
 * GPU acceleration policy. The SDK picks the backend automatically; apps can pin a policy.
 * "GPU" on Android means a Vulkan device (llama.cpp's Vulkan backend); if none is available
 * the SDK falls back to CPU.
 */
sealed class GpuConfig {
    /** Use the GPU when a Vulkan device is present, otherwise CPU. Default. */
    data object Auto : GpuConfig()

    /** Always run on CPU. */
    data object Cpu : GpuConfig()

    /** Offload every layer to the GPU (requires a Vulkan device; falls back to CPU on load failure). */
    data object All : GpuConfig()

    /** Offload exactly [layers] layers to the GPU (0 = CPU). */
    data class Layers(val layers: Int) : GpuConfig()

    /**
     * Maps to llama.cpp's `n_gpu_layers`: 0 = CPU-only, -1 = all layers, N = N layers.
     * [gpuDeviceCount] is the number of Vulkan GPU devices the runtime actually enumerated.
     */
    fun toNGpuLayers(gpuDeviceCount: Int): Int = when (this) {
        is Cpu -> 0
        is All -> -1
        is Layers -> layers
        is Auto -> if (gpuDeviceCount > 0) -1 else 0
    }

    companion object {
        /** Convenience for [Layers]. */
        @JvmStatic
        fun layers(n: Int): GpuConfig = Layers(n)
    }
}

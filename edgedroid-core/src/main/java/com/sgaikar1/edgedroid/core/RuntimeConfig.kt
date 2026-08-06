package com.sgaikar1.edgedroid.core

import com.sgaikar1.edgedroid.common.LogProvider

/**
 * Device-level configuration handed to a [RuntimePlugin] at creation time. The SDK exposes
 * coarse "threading" and "memory" intent; runtimes translate it to their native params.
 */
data class RuntimeConfig(
    val threading: ThreadingConfig = ThreadingConfig(),
    val memory: MemoryConfig = MemoryConfig(),
    val log: LogProvider = LogProvider.NO_OP,
)

data class ThreadingConfig(
    val threads: Int = 4,
    val batchThreads: Int = 4,
) {
    class Builder {
        private var threadsValue: Int = 4
        private var batchThreadsValue: Int = 4
        fun threads(value: Int): Builder = apply { threadsValue = value }
        fun batchThreads(value: Int): Builder = apply { batchThreadsValue = value }
        fun build(): ThreadingConfig = ThreadingConfig(threadsValue, batchThreadsValue)
    }
}

data class MemoryConfig(
    val contextSize: Int = 4096,
    val mmap: Boolean = true,
    val gpu: GpuConfig = GpuConfig.Auto,
    val batchSize: Int = 512,
) {
    class Builder {
        private var contextSizeValue: Int = 4096
        private var mmapValue: Boolean = true
        private var gpuValue: GpuConfig = GpuConfig.Auto
        private var batchSizeValue: Int = 512
        fun contextSize(value: Int): Builder = apply { contextSizeValue = value }
        fun mmap(value: Boolean): Builder = apply { mmapValue = value }
        fun gpu(value: GpuConfig): Builder = apply { gpuValue = value }

        /** Legacy convenience: offload [value] layers to the GPU (0 = CPU). */
        fun gpuLayers(value: Int): Builder = apply { gpuValue = GpuConfig.Layers(value) }
        fun batchSize(value: Int): Builder = apply { batchSizeValue = value }
        fun build(): MemoryConfig = MemoryConfig(contextSizeValue, mmapValue, gpuValue, batchSizeValue)
    }
}

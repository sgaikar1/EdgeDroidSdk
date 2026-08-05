package com.sgaikar1.edgedroid.core

import com.sgaikar1.edgedroid.common.ModelFormat

/**
 * A self-describing runtime provider. New runtimes (ExecuTorch, LiteRT, MNN, ...) are added by
 * shipping a new [RuntimePlugin]; the SDK core is never modified.
 */
interface RuntimePlugin {
    val id: String
    val version: String
    val supportedFormats: Set<ModelFormat>
    val capabilities: Set<Capability>

    suspend fun create(config: RuntimeConfig): Runtime

    companion object {
        const val PRIORITY_DEFAULT = 0
        const val PRIORITY_PREFERRED = 100
    }
}

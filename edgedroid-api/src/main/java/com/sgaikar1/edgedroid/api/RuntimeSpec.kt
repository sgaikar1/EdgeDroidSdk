package com.sgaikar1.edgedroid.api

import com.sgaikar1.edgedroid.core.RuntimePlugin

/**
 * How the SDK should pick a runtime. Apps pass `Runtime.AUTO` today; nothing in the SDK ever
 * hardcodes a runtime identity.
 */
sealed class RuntimeSpec {
    /** Let the SDK choose from registered runtimes based on model format and capabilities. */
    data object Auto : RuntimeSpec()

    /** Select a registered runtime by its plugin id (e.g. "llama", "executorch"). */
    data class ById(val pluginId: String) : RuntimeSpec()

    /** Explicit plugin instance — how the sample app registers the llama runtime for M1. */
    data class ByPlugin(val plugin: RuntimePlugin) : RuntimeSpec()
}

object Runtime {
    val AUTO: RuntimeSpec = RuntimeSpec.Auto
    fun byId(id: String): RuntimeSpec = RuntimeSpec.ById(id)
    fun plugin(plugin: RuntimePlugin): RuntimeSpec = RuntimeSpec.ByPlugin(plugin)
}

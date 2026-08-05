package com.sgaikar1.edgedroid.api

import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.Capability
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimePlugin

/**
 * Holds every registered [RuntimePlugin]. Adding a runtime = registering a plugin, nothing more.
 */
class RuntimeRegistry(private val log: LogProvider = LogProvider.NO_OP) {

    private val plugins = LinkedHashMap<String, RuntimePlugin>()

    @Synchronized
    fun register(plugin: RuntimePlugin): Boolean {
        val replaced = plugins.containsKey(plugin.id)
        plugins[plugin.id] = plugin
        log.log(
            LogProvider.Level.INFO, TAG,
            if (replaced) "Runtime '${plugin.id}' replaced" else "Runtime '${plugin.id}' registered",
            null,
        )
        return replaced
    }

    @Synchronized
    fun unregister(id: String) {
        plugins.remove(id)
    }

    @Synchronized
    fun find(format: ModelFormat, capabilities: Set<Capability> = emptySet()): RuntimePlugin? {
        return plugins.values.firstOrNull {
            format in it.supportedFormats && capabilities.all { c -> c in it.capabilities }
        }
    }

    @Synchronized
    fun byId(id: String): RuntimePlugin? = plugins[id]

    @Synchronized
    fun all(): List<RuntimePlugin> = plugins.values.toList()

    @Synchronized
    fun contains(id: String): Boolean = plugins.containsKey(id)

    suspend fun create(id: String, config: RuntimeConfig): com.sgaikar1.edgedroid.core.Runtime? {
        val plugin = byId(id) ?: return null
        return plugin.create(config)
    }

    companion object {
        private const val TAG = "EdgeDroid.RuntimeRegistry"
    }
}

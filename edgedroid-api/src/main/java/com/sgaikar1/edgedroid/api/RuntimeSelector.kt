package com.sgaikar1.edgedroid.api

import com.sgaikar1.edgedroid.common.FailureKind
import com.sgaikar1.edgedroid.common.SdkResult
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.Runtime
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimePlugin

/**
 * Turns a [RuntimeSpec] + model into a concrete [Runtime]. "AUTO" reads the model's format and
 * required capabilities and picks a registered plugin. Today every selection returns the llama
 * plugin; tomorrow ExecuTorch — the code below does not change.
 */
object RuntimeSelector {

    suspend fun select(
        registry: RuntimeRegistry,
        spec: RuntimeSpec,
        model: Model,
        config: RuntimeConfig,
    ): SdkResult<Runtime> {
        val plugin: RuntimePlugin? = when (spec) {
            is RuntimeSpec.Auto -> registry.find(model.format)
            is RuntimeSpec.ById -> registry.byId(spec.pluginId)
            is RuntimeSpec.ByPlugin -> spec.plugin
        }

        return if (plugin == null) {
            SdkResult.Failure(
                FailureKind.RUNTIME_NOT_FOUND,
                "No runtime registered for model format ${model.format}",
            )
        } else {
            try {
                SdkResult.Success(plugin.create(config))
            } catch (t: Throwable) {
                SdkResult.Failure(
                    FailureKind.RUNTIME_INITIALIZATION,
                    "Runtime '${plugin.id}' failed to initialize: ${t.message}",
                    t,
                )
            }
        }
    }
}

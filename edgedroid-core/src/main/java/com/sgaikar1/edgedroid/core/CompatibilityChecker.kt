package com.sgaikar1.edgedroid.core

/**
 * Answers "can this device run this model?" before the app pays the cost of a large download.
 * Implemented inside the SDK; apps call [com.sgaikar1.edgedroid.api.LlmSdk.models.checkCompatibility].
 */
interface CompatibilityChecker {
    fun check(model: Model, requiredCapabilities: Set<Capability> = emptySet()): CompatibilityReport
}

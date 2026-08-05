package com.sgaikar1.edgedroid.common

/**
 * Logging seam. The SDK is dependency-free on any logger; the app can plug one in via the
 * builder ([com.sgaikar1.edgedroid.api.LlmSdk.Builder.logging]).
 */
interface LogProvider {
    fun log(level: Level, tag: String, message: String, throwable: Throwable?)

    fun log(level: Level, tag: String, message: String) = log(level, tag, message, null)

    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    companion object {
        val NO_OP = object : LogProvider {
            override fun log(level: Level, tag: String, message: String, throwable: Throwable?) = Unit
        }
    }
}

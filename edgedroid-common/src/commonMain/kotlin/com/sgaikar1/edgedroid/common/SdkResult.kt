package com.sgaikar1.edgedroid.common

/**
 * Thin result wrapper used across the SDK boundary so failures carry a machine-readable kind
 * plus a human message without leaking exceptions.
 */
sealed interface SdkResult<out T> {
    data class Success<T>(val value: T) : SdkResult<T>
    data class Failure(val kind: FailureKind, val message: String, val cause: Throwable? = null) : SdkResult<Nothing>

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw (cause ?: RuntimeException(message))
    }
}

enum class FailureKind {
    RUNTIME_NOT_FOUND,
    RUNTIME_INITIALIZATION,
    MODEL_NOT_FOUND,
    DOWNLOAD_FAILED,
    VERIFICATION_FAILED,
    IO,
    CANCELLED,
    UNKNOWN,
}

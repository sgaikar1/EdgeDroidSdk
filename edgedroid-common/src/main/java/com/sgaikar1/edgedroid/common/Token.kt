package com.sgaikar1.edgedroid.common

/**
 * A single emitted token of a generation stream. Runtimes report plain text pieces so the
 * SDK (and app) never care about the underlying tokenizer.
 */
data class Token(
    val index: Long,
    val id: Long,
    val text: String,
)

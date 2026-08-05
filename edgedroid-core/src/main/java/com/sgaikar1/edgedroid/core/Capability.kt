package com.sgaikar1.edgedroid.core

/**
 * Runtime capabilities. Runtimes declare what they support via
 * [RuntimePlugin.capabilities]; the SDK dispatches on capabilities, never on runtime identity.
 */
enum class Capability {
    STREAMING,
    VISION,
    EMBEDDINGS,
    TOOL_CALLING,
    JSON_MODE,
    GRAMMAR,
    AUDIO,
}

package com.sgaikar1.edgedroid.common

import kotlinx.serialization.Serializable

/**
 * Supported on-device model formats. The SDK core never interprets a format; it only
 * forwards the format to the runtime registry so the right [com.sgaikar1.edgedroid.core.RuntimePlugin]
 * can be selected.
 */
@Serializable
enum class ModelFormat {
    GGUF,
    PTE,
    TFLITE,
    MNN,
    ONNX,
    CUSTOM,
}

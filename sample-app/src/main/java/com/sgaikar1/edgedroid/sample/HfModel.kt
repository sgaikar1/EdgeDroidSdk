package com.sgaikar1.edgedroid.sample

import com.sgaikar1.edgedroid.common.ModelFormat
import java.io.Serializable

/** A model card from the HuggingFace Hub API. */
data class HfModelSummary(
    val id: String,
    val downloads: Long,
    val likes: Long,
)

/** A file inside an HF repo. */
data class HfFile(
    val name: String,
    val size: Long,
)

/**
 * A model the user picked from the HF browser: a specific repo file + format.
 */
data class HfModelSelection(
    val repoId: String,
    val fileName: String,
    val format: ModelFormat,
    val sizeBytes: Long?,
) : Serializable {
    val label: String get() = "$repoId / $fileName"
}

/** Whether [format] is a chat-capable GGUF (llama) or embedding-capable ONNX model. */
val ModelFormat.isOnnx: Boolean get() = this == ModelFormat.ONNX

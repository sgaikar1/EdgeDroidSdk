package com.sgaikar1.edgedroid.core

import com.sgaikar1.edgedroid.common.ModelFormat
import kotlinx.serialization.Serializable

/**
 * A model identity, independent of any runtime or file location. Models can be remote
 * (downloaded on demand) or local (already on device).
 */
@Serializable
data class Model(
    val id: String,
    val name: String,
    val version: String = "1.0",
    val sizeBytes: Long? = null,
    val format: ModelFormat,
    val downloadUrl: String? = null,
    val sha256: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    /** Absolute path once the model is materialized on device; null until stored/downloaded. */
    val localPath: String?
        get() = metadata["localPath"]

    companion object {
        fun remote(
            id: String,
            url: String,
            sha256: String? = null,
            format: ModelFormat = ModelFormat.GGUF,
            name: String = id,
            version: String = "1.0",
            sizeBytes: Long? = null,
            metadata: Map<String, String> = emptyMap(),
        ): Model = Model(
            id = id,
            name = name,
            version = version,
            sizeBytes = sizeBytes,
            format = format,
            downloadUrl = url,
            sha256 = sha256,
            metadata = metadata,
        )

        fun local(path: String, format: ModelFormat = ModelFormat.GGUF, id: String = path): Model =
            Model(id = id, name = id, format = format, metadata = mapOf("localPath" to path))
    }
}

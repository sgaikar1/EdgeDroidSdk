package com.sgaikar1.edgedroid.storage

import com.sgaikar1.edgedroid.common.ModelFormat
import kotlinx.serialization.Serializable

@Serializable
internal data class ModelEntry(
    val id: String,
    val name: String,
    val version: String,
    val sizeBytes: Long?,
    val format: ModelFormat,
    val downloadUrl: String?,
    val sha256: String?,
    val metadata: Map<String, String>,
)

@Serializable
internal data class MetadataFile(
    val version: Int = 1,
    val models: List<ModelEntry> = emptyList(),
)

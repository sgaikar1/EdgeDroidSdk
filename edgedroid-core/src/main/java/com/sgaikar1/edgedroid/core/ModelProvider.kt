package com.sgaikar1.edgedroid.core

import kotlinx.coroutines.flow.Flow

/**
 * Resolves a [Model] into a local, verified file, downloading first if needed.
 */
interface ModelProvider {
    fun availableModels(): List<Model>
    fun download(model: Model): Flow<ModelDownloadState>
    suspend fun getLocalPath(model: Model): String
    fun resolve(id: String): Model?
    fun adoptLocal(modelId: String, filePath: String): Model
    suspend fun delete(modelId: String)
}

package com.sgaikar1.edgedroid.core

import kotlinx.coroutines.flow.Flow

/**
 * Model acquisition. The app never sees HTTP, file IO or verification — it just observes
 * [Flow] of [ModelDownloadState] or calls the blocking convenience on the SDK facade.
 */
interface Downloader {
    fun download(model: Model): Flow<ModelDownloadState>
    suspend fun pause(modelId: String)
    suspend fun resume(modelId: String)
    suspend fun cancel(modelId: String)
    suspend fun delete(modelId: String)
    fun stateFor(modelId: String): ModelDownloadState?
}

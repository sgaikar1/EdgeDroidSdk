package com.sgaikar1.edgedroid.core

/**
 * Download lifecycle states surfaced to the app as [kotlinx.coroutines.flow.Flow].
 */
sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Downloading(
        val bytesReceived: Long,
        val totalBytes: Long?,
        val progress: Float,
    ) : ModelDownloadState
    data class Paused(val bytesReceived: Long) : ModelDownloadState
    data class Completed(val localPath: String) : ModelDownloadState
    data class Failed(val kind: String, val message: String) : ModelDownloadState
    data object Cancelled : ModelDownloadState
}

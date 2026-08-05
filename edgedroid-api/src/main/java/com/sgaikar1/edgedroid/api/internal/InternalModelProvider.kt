package com.sgaikar1.edgedroid.api.internal

import com.sgaikar1.edgedroid.common.FailureKind
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.common.SdkResult
import com.sgaikar1.edgedroid.core.Downloader
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.ModelDownloadState
import com.sgaikar1.edgedroid.core.ModelProvider
import com.sgaikar1.edgedroid.core.ModelStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

internal class InternalModelProvider(
    private val storage: ModelStorage,
    private val downloader: Downloader,
    private val log: LogProvider,
) : ModelProvider {

    override fun availableModels(): List<Model> = storage.allModels()

    override fun download(model: Model): Flow<ModelDownloadState> = downloader.download(model)

    override suspend fun getLocalPath(model: Model): String {
        if (storage.isDownloaded(model)) {
            val cached = storage.modelPath(model).absolutePath
            log.log(LogProvider.Level.DEBUG, TAG, "Model '${model.id}' already on device at $cached")
            return cached
        }
        val url = model.downloadUrl
        if (url.isNullOrBlank()) {
            throw RuntimeException("Model '${model.id}' is not on device and has no downloadUrl")
        }
        log.log(LogProvider.Level.INFO, TAG, "Downloading model '${model.id}' from $url")
        var failure: Throwable? = null
        var completedPath: String? = null
        downloader.download(model).collect { state ->
            when (state) {
                is ModelDownloadState.Completed -> completedPath = state.localPath
                is ModelDownloadState.Failed -> failure =
                    RuntimeException("${state.kind}: ${state.message}")
                else -> Unit
            }
        }
        failure?.let { throw it }
        return completedPath ?: throw RuntimeException("Model download did not complete")
    }

    override fun resolve(id: String): Model? = storage.resolve(id)

    override fun adoptLocal(modelId: String, filePath: String): Model {
        val model = Model.local(path = filePath, id = modelId)
        storage.record(model, filePath)
        return model
    }

    override suspend fun delete(modelId: String) {
        downloader.delete(modelId)
        storage.resolve(modelId)?.let { storage.delete(it) }
    }

    companion object {
        private const val TAG = "EdgeDroid.ModelProvider"
    }
}

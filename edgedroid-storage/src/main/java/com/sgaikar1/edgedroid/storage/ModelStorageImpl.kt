package com.sgaikar1.edgedroid.storage

import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.ModelStorage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Filesystem-backed [ModelStorage]. Metadata lives in `models/metadata.json` (kotlinx-serialization).
 */
class ModelStorageImpl(
    paths: StoragePaths,
    private val log: LogProvider = LogProvider.NO_OP,
) : ModelStorage {

    override val rootDir: File = paths.rootDir
    override val modelsDir: File = paths.modelsDir
    override val downloadsDir: File = paths.downloadsDir
    override val cacheDir: File = paths.cacheDir
    override val tempDir: File = paths.tempDir

    private val metadataFile: File = File(modelsDir, "metadata.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun modelPath(model: Model): File = File(modelsDir, fileNameFor(model.id, model.format))

    @Synchronized
    override fun isDownloaded(model: Model): Boolean {
        val entry = entryFor(model.id) ?: return false
        val file = File(entry.metadata["localPath"] ?: return false)
        return file.exists() && file.isFile
    }

    @Synchronized
    override fun record(model: Model, localPath: String) {
        val updated = readEntries().mapNotNull { existing ->
            if (existing.id == model.id) null else existing
        } + ModelEntry(
            id = model.id,
            name = model.name,
            version = model.version,
            sizeBytes = model.sizeBytes,
            format = model.format,
            downloadUrl = model.downloadUrl,
            sha256 = model.sha256,
            metadata = model.metadata + ("localPath" to localPath),
        )
        writeEntries(updated)
        log.log(LogProvider.Level.DEBUG, TAG, "Recorded model '${model.id}' at $localPath")
    }

    @Synchronized
    override fun resolve(id: String): Model? {
        val entry = entryFor(id) ?: return null
        return entry.toModel()
    }

    @Synchronized
    override fun allModels(): List<Model> = readEntries().map { it.toModel() }

    @Synchronized
    override fun delete(model: Model): Boolean {
        val entry = entryFor(model.id)
        if (entry != null) {
            File(entry.metadata["localPath"] ?: "").delete()
        }
        val updated = readEntries().filterNot { it.id == model.id }
        writeEntries(updated)
        return true
    }

    private fun entryFor(id: String): ModelEntry? = readEntries().firstOrNull { it.id == id }

    private fun readEntries(): List<ModelEntry> {
        if (!metadataFile.exists()) return emptyList()
        return try {
            json.decodeFromString<MetadataFile>(metadataFile.readText()).models
        } catch (t: Throwable) {
            log.log(LogProvider.Level.WARN, TAG, "Corrupt metadata, ignoring: ${t.message}")
            emptyList()
        }
    }

    private fun writeEntries(entries: List<ModelEntry>) {
        metadataFile.writeText(json.encodeToString(MetadataFile(models = entries)))
    }

    private fun ModelEntry.toModel(): Model = Model(
        id = id,
        name = name,
        version = version,
        sizeBytes = sizeBytes,
        format = format,
        downloadUrl = downloadUrl,
        sha256 = sha256,
        metadata = metadata,
    )

    companion object {
        private const val TAG = "EdgeDroid.Storage"

        fun fileNameFor(id: String, format: ModelFormat): String {
            val safe = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return "$safe${extensionFor(format)}"
        }

        fun extensionFor(format: ModelFormat): String = when (format) {
            ModelFormat.GGUF -> ".gguf"
            ModelFormat.PTE -> ".pte"
            ModelFormat.TFLITE -> ".tflite"
            ModelFormat.MNN -> ".mnn"
            ModelFormat.ONNX -> ".onnx"
            ModelFormat.CUSTOM -> ".bin"
        }
    }
}

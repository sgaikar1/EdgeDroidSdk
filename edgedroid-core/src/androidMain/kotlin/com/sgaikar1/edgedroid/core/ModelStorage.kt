package com.sgaikar1.edgedroid.core

import java.io.File

/**
 * Persistent, app-private model storage. Layout is owned by the SDK:
 *   models/  downloads/  cache/  temp/
 * A runtime only ever receives an [absolutePath].
 */
interface ModelStorage {
    val rootDir: File
    val modelsDir: File
    val downloadsDir: File
    val cacheDir: File
    val tempDir: File

    fun modelPath(model: Model): File
    fun isDownloaded(model: Model): Boolean
    fun record(model: Model, localPath: String)
    fun resolve(id: String): Model?
    fun allModels(): List<Model>
    fun delete(model: Model): Boolean
}

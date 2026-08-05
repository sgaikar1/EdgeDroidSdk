package com.sgaikar1.edgedroid.storage

import android.content.Context
import java.io.File

/**
 * The SDK-owned on-device layout. Everything lives under app-private files so no storage
 * permission is ever needed:
 *
 *   models/     final, verified model files
 *   downloads/  partial + in-progress downloads (survive pause/resume)
 *   cache/      reusable artifacts
 *   temp/       scratch space
 */
class StoragePaths(context: Context) {

    private val baseDir: File = context.filesDir

    val rootDir: File = baseDir
    val modelsDir: File = File(baseDir, "models")
    val downloadsDir: File = File(baseDir, "downloads")
    val cacheDir: File = File(baseDir, "cache")
    val tempDir: File = File(baseDir, "temp")

    init {
        modelsDir.mkdirs()
        downloadsDir.mkdirs()
        cacheDir.mkdirs()
        tempDir.mkdirs()
    }
}

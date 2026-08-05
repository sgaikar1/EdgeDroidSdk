package com.sgaikar1.edgedroid.api.internal

import com.sgaikar1.edgedroid.core.Cache
import java.io.File
import java.security.MessageDigest

internal class DefaultCache(
    private val cacheDir: File,
) : Cache {

    override fun get(key: String): File? {
        val file = fileForKey(key)
        return file.takeIf { it.exists() }
    }

    override fun put(key: String, data: ByteArray): File {
        val file = fileForKey(key)
        file.parentFile?.mkdirs()
        file.writeBytes(data)
        return file
    }

    override fun evict(key: String) {
        fileForKey(key).delete()
    }

    override fun clear() {
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun fileForKey(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$digest.cache")
    }
}

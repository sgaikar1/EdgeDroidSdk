package com.sgaikar1.edgedroid.core

import java.io.File

/**
 * Cache seam for reusable model artifacts (e.g. mmap pages, pre-tokenized prompts). Default SDK
 * implementation is a simple filesystem cache keyed by string.
 */
interface Cache {
    fun get(key: String): File?
    fun put(key: String, data: ByteArray): File
    fun evict(key: String)
    fun clear()
}

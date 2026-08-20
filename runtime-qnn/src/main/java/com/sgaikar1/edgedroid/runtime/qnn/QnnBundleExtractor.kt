package com.sgaikar1.edgedroid.runtime.qnn

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Bridges EdgeDroid's single-file model downloads and the GenieX `qairt` runtime, which expects
 * a QNN bundle as an **extracted directory**. Qualcomm AI Hub ships bundles as archives (ZIP or
 * gzip-compressed TAR); EdgeDroid stores the download under a single file
 * (`<modelId>.bin` via [com.sgaikar1.edgedroid.storage.ModelStorageImpl.extensionFor]).
 *
 * [QnnRuntime.loadModel] sniffs the downloaded file's magic bytes and, when it is an archive,
 * extracts it into the app cache directory before handing the directory to GenieX. Non-archives
 * are left untouched and passed through as-is.
 *
 * Zero-dependency: uses `java.util.zip` for ZIP and a small TAR reader over `GZIPInputStream`.
 * All extraction is path-traversal-safe — entries that would escape the destination directory
 * are skipped.
 */
internal object QnnBundleExtractor {

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B)          // "PK"
    private val GZIP_MAGIC = byteArrayOf(0x1F.toByte(), 0x8B.toByte())

    /**
     * True when [file] starts with a recognized archive magic (ZIP or gzip). Conservative:
     * a non-archive model file is not misidentified.
     */
    fun looksLikeArchive(file: File): Boolean {
        if (!file.isFile || file.length() < 2L) return false
        val magic = readMagic(file) ?: return false
        return isZip(magic) || isGzip(magic)
    }

    /**
     * Extracts [archive] into [destDir] (created if missing) and returns [destDir].
     *
     * @throws IllegalArgumentException when [archive] is not a supported archive or is truncated.
     */
    fun extract(archive: File, destDir: File): File {
        val magic = readMagic(archive)
            ?: throw IllegalArgumentException("Not a readable QNN bundle archive: ${archive.name}")
        destDir.mkdirs()
        when {
            isZip(magic) -> extractZip(archive, destDir)
            isGzip(magic) -> extractTarGz(archive, destDir)
            else -> throw IllegalArgumentException("Unsupported QNN bundle archive format: ${archive.name}")
        }
        return destDir
    }

    // --- ZIP ---

    private fun extractZip(archive: File, destDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = safeTarget(destDir, entry.name)
                if (target == null) {
                    zip.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
    }

    // --- gzip-compressed TAR ---

    private fun extractTarGz(archive: File, destDir: File) {
        val input = BufferedInputStream(GZIPInputStream(FileInputStream(archive), 1 shl 16), 1 shl 16)
        input.use { tar ->
            var pendingLongName: String? = null
            while (true) {
                val header = ByteArray(512)
                val read = readFully(tar, header)
                if (read == 0) break
                if (read < 512) throw IllegalArgumentException("Truncated TAR header in ${archive.name}")
                // Two consecutive zero blocks mark the end of the archive.
                if (header.all { it == 0.toByte() }) break

                val size = tarSize(header)
                val type = header[TAR_TYPE_OFFSET].toInt().toChar()

                // GNU long name: its payload is the name of the next entry.
                if (type == 'L') {
                    pendingLongName = readLongName(tar, size)
                    skipFully(tar, (512 - (size % 512)) % 512)
                    continue
                }

                val name = pendingLongName ?: tarName(header)
                pendingLongName = null
                val target = safeTarget(destDir, name)
                if (target == null) {
                    skipFully(tar, size)
                } else {
                    when (type) {
                        '5' -> target.mkdirs() // directory
                        '0', '\u0000', ' ' -> {
                            // regular file
                            target.parentFile?.mkdirs()
                            copyExact(tar, target, size)
                        }
                        // 'x'/'g' pax headers and anything else: skip the payload.
                        else -> skipFully(tar, size)
                    }
                }
                skipFully(tar, (512 - (size % 512)) % 512)
            }
        }
    }

    // --- helpers ---

    private fun readMagic(file: File): ByteArray? = runCatching {
        file.inputStream().use { input ->
            val magic = ByteArray(2)
            val read = input.read(magic)
            if (read == 2) magic else null
        }
    }.getOrNull()

    private fun isZip(magic: ByteArray): Boolean = magic[0] == ZIP_MAGIC[0] && magic[1] == ZIP_MAGIC[1]

    private fun isGzip(magic: ByteArray): Boolean = magic[0] == GZIP_MAGIC[0] && magic[1] == GZIP_MAGIC[1]

    /**
     * Resolves [rawName] inside [destDir], rejecting any entry that would escape it
     * (absolute paths, drive letters, `..` traversal). Returns null to skip the entry.
     */
    private fun safeTarget(destDir: File, rawName: String): File? {
        val clean = rawName.replace('\\', '/')
            .trimStart('/')
            .let { if (it.contains(':')) it.substringAfter(':') else it }
        if (clean.isEmpty() || clean == ".") return null
        val target = File(destDir, clean)
        val base = destDir.canonicalFile
        val resolved = target.canonicalFile
        return if (resolved.path == base.path || resolved.path.startsWith(base.path + File.separator)) {
            target
        } else {
            null
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) break
            offset += n
        }
        return offset
    }

    private fun copyExact(input: InputStream, target: File, length: Long) {
        FileOutputStream(target).use { out ->
            var remaining = length
            val buffer = ByteArray(1 shl 16)
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) throw IllegalArgumentException("Truncated TAR entry '${target.name}'")
                out.write(buffer, 0, n)
                remaining -= n
            }
        }
    }

    private fun skipFully(input: InputStream, length: Long) {
        var remaining = length
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() == -1) throw IllegalArgumentException("Truncated archive")
                remaining -= 1
            }
        }
    }

    private fun tarName(header: ByteArray): String {
        var end = TAR_NAME_OFFSET
        while (end < TAR_NAME_OFFSET + TAR_NAME_LENGTH && header[end] != 0.toByte()) end++
        return String(header, TAR_NAME_OFFSET, end - TAR_NAME_OFFSET, Charsets.UTF_8)
    }

    private fun tarSize(header: ByteArray): Long {
        val field = String(header, TAR_SIZE_OFFSET, 12, Charsets.US_ASCII).trim('\u0000', ' ')
        return field.toLongOrNull(8) ?: 0L
    }

    private fun readLongName(input: InputStream, length: Long): String {
        val buffer = ByteArray(length.toInt())
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) break
            offset += n
        }
        return String(buffer, 0, offset, Charsets.UTF_8).trimEnd('\u0000')
    }

    private const val TAR_NAME_OFFSET = 0
    private const val TAR_NAME_LENGTH = 100
    private const val TAR_SIZE_OFFSET = 124
    private const val TAR_TYPE_OFFSET = 156
}
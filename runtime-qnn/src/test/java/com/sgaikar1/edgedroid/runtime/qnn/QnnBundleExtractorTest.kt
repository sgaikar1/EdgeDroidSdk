package com.sgaikar1.edgedroid.runtime.qnn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class QnnBundleExtractorTest {

    private val tempDir: File get() = createTempDir()

    // --- magic sniffing ---

    @Test
    fun `looksLikeArchive detects zip and gzip and rejects plain files`() {
        val zip = tempDir.resolve("bundle.zip").apply { writeBytes(zipBytes(listOf("model.bin" to byteArrayOf(1)))) }
        val tarGz = tempDir.resolve("bundle.tar.gz").apply { writeBytes(tarGzBytes(listOf("model.bin" to byteArrayOf(2)))) }
        val plain = tempDir.resolve("model.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        val empty = tempDir.resolve("empty").apply { writeBytes(ByteArray(0)) }

        assertTrue(QnnBundleExtractor.looksLikeArchive(zip))
        assertTrue(QnnBundleExtractor.looksLikeArchive(tarGz))
        assertFalse(QnnBundleExtractor.looksLikeArchive(plain))
        assertFalse(QnnBundleExtractor.looksLikeArchive(empty))
    }

    @Test
    fun `extract rejects unknown formats`() {
        val plain = tempDir.resolve("model.bin").apply { writeText("not an archive") }
        try {
            QnnBundleExtractor.extract(plain, tempDir.resolve("out"))
            fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    // --- ZIP ---

    @Test
    fun `extracts a zip archive preserving structure`() {
        val archive = tempDir.resolve("bundle.zip").apply {
            writeBytes(zipBytes(listOf("model.bin" to byteArrayOf(9, 8, 7), "config.json" to "{}".toByteArray())))
        }
        val dest = tempDir.resolve("out")
        QnnBundleExtractor.extract(archive, dest)

        assertArrayEquals(byteArrayOf(9, 8, 7), dest.resolve("model.bin").readBytes())
        assertEquals("{}", dest.resolve("config.json").readText())
    }

    @Test
    fun `zip extraction rejects path traversal`() {
        val evil = tempDir.resolve("evil.zip").apply {
            writeBytes(zipBytes(listOf("../escaped.txt" to byteArrayOf(1))))
        }
        val dest = tempDir.resolve("out")
        QnnBundleExtractor.extract(evil, dest)

        assertFalse(tempDir.resolve("escaped.txt").exists())
        assertEquals(0, dest.listFiles()?.size ?: 0)
    }

    // --- gzip-compressed TAR ---

    @Test
    fun `extracts a tar gz archive with nested directories`() {
        val archive = tempDir.resolve("bundle.tar.gz").apply {
            writeBytes(
                tarGzBytes(
                    listOf(
                        "model/" to null,
                        "model/model.bin" to byteArrayOf(1, 2, 3),
                        "model/tokenizer.json" to "{}".toByteArray(),
                    ),
                ),
            )
        }
        val dest = tempDir.resolve("out")
        QnnBundleExtractor.extract(archive, dest)

        assertTrue(dest.resolve("model").isDirectory)
        assertArrayEquals(byteArrayOf(1, 2, 3), dest.resolve("model/model.bin").readBytes())
        assertEquals("{}", dest.resolve("model/tokenizer.json").readText())
    }

    @Test
    fun `tar extraction rejects path traversal`() {
        val evil = tempDir.resolve("evil.tar.gz").apply {
            writeBytes(tarGzBytes(listOf("../escaped.bin" to byteArrayOf(1))))
        }
        val dest = tempDir.resolve("out")
        QnnBundleExtractor.extract(evil, dest)

        assertFalse(tempDir.resolve("escaped.bin").exists())
        assertEquals(0, dest.listFiles()?.size ?: 0)
    }

    @Test
    fun `tar extraction handles gnu long names`() {
        val longName = "a/".repeat(60) + "model.bin" // > 100 chars
        val archive = tempDir.resolve("long.tar.gz").apply {
            writeBytes(tarGzBytes(listOf(longName to byteArrayOf(42)), useLongName = true))
        }
        val dest = tempDir.resolve("out")
        QnnBundleExtractor.extract(archive, dest)

        val target = dest.resolve(longName)
        assertTrue(target.isFile)
        assertArrayEquals(byteArrayOf(42), target.readBytes())
    }

    // --- test fixture writers ---

    private fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun tarGzBytes(entries: List<Pair<String, ByteArray?>>, useLongName: Boolean = false): ByteArray {
        val tar = ByteArrayOutputStream()
        entries.forEach { (name, data) ->
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            if (useLongName && nameBytes.size > 100) {
                // GNU 'L' long-name entry followed by the real entry.
                tar.write(tarHeader(name = "././@LongLink", size = nameBytes.size.toLong(), type = 'L'))
                tar.write(nameBytes)
                tar.write(ByteArray((512 - (nameBytes.size % 512)) % 512))
            }
            if (data == null) {
                tar.write(tarHeader(name = name, size = 0L, type = '5'))
            } else {
                tar.write(tarHeader(name = name, size = data.size.toLong(), type = '0'))
                tar.write(data)
                tar.write(ByteArray((512 - (data.size % 512)) % 512))
            }
        }
        tar.write(ByteArray(1024)) // end-of-archive zero blocks
        val bytes = tar.toByteArray()
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(bytes) }
        return gz.toByteArray()
    }

    private fun tarHeader(name: String, size: Long, type: Char): ByteArray {
        val header = ByteArray(512)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
        System.arraycopy("0000644\u0000".toByteArray(Charsets.US_ASCII), 0, header, 100, 8) // mode
        // uid/gid at 108/116 stay zero.
        val sizeField = size.toString(8).padStart(11, '0') + "\u0000"
        System.arraycopy(sizeField.toByteArray(Charsets.US_ASCII), 0, header, 124, 12)
        // mtime at 136 stays zero.
        for (i in 148 until 156) header[i] = ' '.code.toByte() // checksum field = spaces for the sum
        header[156] = type.code.toByte()
        val sum = header.sumOf { it.toInt() and 0xff }
        val checksum = sum.toString(8).padStart(6, '0') + "\u0000 "
        System.arraycopy(checksum.toByteArray(Charsets.US_ASCII), 0, header, 148, 8)
        return header
    }
}
package com.sgaikar1.edgedroid.api.internal

import com.sgaikar1.edgedroid.api.RuntimeRegistry
import com.sgaikar1.edgedroid.api.RuntimeSpec
import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.Capability
import com.sgaikar1.edgedroid.core.CompatibilityIssue
import com.sgaikar1.edgedroid.core.CompatibilitySeverity
import com.sgaikar1.edgedroid.core.DeviceCapabilities
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.ModelStorage
import com.sgaikar1.edgedroid.core.Runtime
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimePlugin
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCompatibilityCheckerTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    private val llamaPlugin = object : RuntimePlugin {
        override val id = "llama"
        override val version = "test"
        override val supportedFormats = setOf(ModelFormat.GGUF)
        override val capabilities = setOf(Capability.STREAMING)
        override val supportedAbis = setOf("arm64-v8a", "x86_64")
        override suspend fun create(config: RuntimeConfig): Runtime = TODO("not used in tests")
    }

    private class FakeStorage(private val downloadedIds: Set<String> = emptySet()) : ModelStorage {
        override val rootDir: File = File("/tmp/root")
        override val modelsDir: File = File(rootDir, "models")
        override val downloadsDir: File = File(rootDir, "downloads")
        override val cacheDir: File = File(rootDir, "cache")
        override val tempDir: File = File(rootDir, "temp")
        override fun modelPath(model: Model): File = File(modelsDir, model.id + ".gguf")
        override fun isDownloaded(model: Model): Boolean = model.id in downloadedIds
        override fun record(model: Model, localPath: String) {}
        override fun resolve(id: String): Model? = null
        override fun allModels(): List<Model> = emptyList()
        override fun delete(model: Model): Boolean = true
    }

    private fun model(
        size: Long? = 1 * gb,
        format: ModelFormat = ModelFormat.GGUF,
        url: String? = "https://example.com/m.gguf",
    ): Model = Model(
        id = "m",
        name = "m",
        format = format,
        sizeBytes = size,
        downloadUrl = url,
    )

    private fun registryWithLlama(): RuntimeRegistry = RuntimeRegistry().also { it.register(llamaPlugin) }

    private fun checker(
        device: DeviceCapabilities,
        storage: ModelStorage = FakeStorage(),
        registry: RuntimeRegistry = registryWithLlama(),
    ): DefaultCompatibilityChecker = DefaultCompatibilityChecker(device, storage, registry, RuntimeSpec.Auto)

    private fun device(
        free: Long = 5 * gb,
        ram: Long = 8 * gb,
        abis: List<String> = listOf("arm64-v8a"),
    ): DeviceCapabilities = DeviceCapabilities(
        supportedAbis = abis,
        totalRamBytes = ram,
        availableRamBytes = ram / 2,
        freeStorageBytes = free,
        cpuCores = 8,
    )

    @Test
    fun `insufficient storage is a hard error and blocks download`() {
        val report = checker(device(free = 512 * mb)).check(model(size = 1 * gb))

        assertFalse(report.isDownloadable)
        assertTrue(report.errors.any { it.code == CompatibilityIssue.CODE_INSUFFICIENT_STORAGE })
        assertTrue(report.errors.first { it.code == CompatibilityIssue.CODE_INSUFFICIENT_STORAGE }
            .severity == CompatibilitySeverity.ERROR)
    }

    @Test
    fun `enough storage is downloadable`() {
        val report = checker(device(free = 5 * gb)).check(model(size = 1 * gb))
        assertTrue(report.isDownloadable)
        assertFalse(report.errors.any { it.code == CompatibilityIssue.CODE_INSUFFICIENT_STORAGE })
    }

    @Test
    fun `unsupported format is a hard error`() {
        val report = checker(device()).check(model(format = ModelFormat.ONNX))
        assertFalse(report.isDownloadable)
        assertTrue(report.errors.any { it.code == CompatibilityIssue.CODE_NO_RUNTIME })
    }

    @Test
    fun `low ram is a warning, not a blocker`() {
        // 7 GB model on 8 GB RAM (above the 75% threshold) with plenty of storage.
        val report = checker(device(ram = 8 * gb, free = 20 * gb)).check(model(size = 7 * gb))
        assertTrue(report.isDownloadable)
        assertTrue(report.warnings.any { it.code == CompatibilityIssue.CODE_LOW_RAM })
        assertTrue(report.errors.none { it.code == CompatibilityIssue.CODE_LOW_RAM })
    }

    @Test
    fun `abi mismatch is a warning, not a blocker`() {
        val report = checker(device(abis = listOf("x86"))).check(model())
        assertTrue(report.isDownloadable)
        assertTrue(report.warnings.any { it.code == CompatibilityIssue.CODE_ABI_MISMATCH })
    }

    @Test
    fun `unknown model size warns instead of blocking`() {
        val report = checker(device(free = 10 * mb)).check(model(size = null))
        assertTrue(report.isDownloadable)
        assertTrue(report.warnings.any { it.code == CompatibilityIssue.CODE_UNKNOWN_MODEL_SIZE })
    }

    @Test
    fun `already downloaded model skips storage check`() {
        val report = checker(
            device(free = 10 * mb),
            storage = FakeStorage(downloadedIds = setOf("m")),
        ).check(model(size = 1 * gb))
        assertTrue(report.isDownloadable)
        assertFalse(report.errors.any { it.code == CompatibilityIssue.CODE_INSUFFICIENT_STORAGE })
    }

    @Test
    fun `model neither on device nor downloadable is a hard error`() {
        val report = checker(device()).check(model(url = null))
        assertFalse(report.isLoadable)
        assertTrue(report.errors.any { it.code == CompatibilityIssue.CODE_MODEL_FILE_MISSING })
    }

    @Test
    fun `report exposes informational core count`() {
        val report = checker(device()).check(model())
        assertEquals(8, report.info.single { it.code == CompatibilityIssue.CODE_CPU_CORES }
            .message.let { msg -> Regex("\\d+").find(msg)?.value?.toInt() })
    }
}

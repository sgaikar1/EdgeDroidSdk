package com.sgaikar1.edgedroid.sample

import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.DeviceCapabilities
import com.sgaikar1.edgedroid.core.GpuConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityTest {

    private val gb = 1024L * 1024 * 1024

    private fun caps(
        ram: Long = 8 * gb,
        storage: Long = 32 * gb,
        cores: Int = 8,
        vulkan: Boolean = true,
        abis: List<String> = listOf("arm64-v8a"),
    ) = DeviceCapabilities(
        supportedAbis = abis,
        totalRamBytes = ram,
        freeStorageBytes = storage,
        cpuCores = cores,
        vulkanSupported = vulkan,
    )

    @Test
    fun `recommended uses cores for threads`() {
        val c = SampleConfig.recommended(caps(cores = 6))
        assertEquals(6, c.threads)
        assertEquals(8, SampleConfig.recommended(caps(cores = 12)).threads)
        assertEquals(2, SampleConfig.recommended(caps(cores = 1)).threads)
    }

    @Test
    fun `recommended context from ram`() {
        assertEquals(4096, SampleConfig.recommended(caps(ram = 8 * gb)).contextSize)
        assertEquals(4096, SampleConfig.recommended(caps(ram = 12 * gb)).contextSize)
        assertEquals(2048, SampleConfig.recommended(caps(ram = 4 * gb)).contextSize)
        assertEquals(1024, SampleConfig.recommended(caps(ram = 2 * gb)).contextSize)
    }

    @Test
    fun `recommended gpu follows vulkan`() {
        assertEquals(GpuConfig.Auto, SampleConfig.recommended(caps(vulkan = true)).gpu)
        assertEquals(GpuConfig.Cpu, SampleConfig.recommended(caps(vulkan = false)).gpu)
    }

    @Test
    fun `fitsDevice accepts models within storage`() {
        val smoll = SampleModels.byId("smollm2-135m-instruct")
        assertTrue(smoll.fitsDevice(caps(storage = 1 * gb)))
        assertFalse(smoll.fitsDevice(caps(storage = 10 * 1024 * 1024L)))
    }

    @Test
    fun `unknown size is treated as fitting`() {
        val m = SampleModels.byId("smollm2-135m-instruct").copy(sizeBytes = null)
        assertTrue(m.fitsDevice(caps(storage = 1 * 1024 * 1024L)))
    }

    @Test
    fun `deviceFit blocks oversized files`() {
        val (fit, _) = deviceFit(
            HfFile("model.gguf", size = 8 * gb),
            ModelFormat.GGUF,
            caps(storage = 2 * gb),
        )
        assertEquals(DeviceFit.BLOCKED_STORAGE, fit)
    }

    @Test
    fun `deviceFit flags ram heavy files as warning`() {
        val (fit, _) = deviceFit(
            HfFile("model.gguf", size = 7 * gb),
            ModelFormat.GGUF,
            caps(ram = 8 * gb, storage = 64 * gb),
        )
        assertEquals(DeviceFit.LARGE_FOR_RAM, fit)
    }

    @Test
    fun `deviceFit blocks unsupported abi`() {
        val (fit, _) = deviceFit(
            HfFile("model.gguf", size = 1),
            ModelFormat.GGUF,
            caps(storage = 64 * gb, abis = listOf("x86")),
        )
        assertEquals(DeviceFit.BLOCKED_RUNTIME, fit)
    }

    @Test
    fun `deviceFit accepts fitting files`() {
        val (fit, _) = deviceFit(
            HfFile("model.gguf", size = 500 * 1024 * 1024L),
            ModelFormat.GGUF,
            caps(ram = 8 * gb, storage = 8 * gb),
        )
        assertEquals(DeviceFit.FITS, fit)
    }
}

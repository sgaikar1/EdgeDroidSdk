package com.sgaikar1.edgedroid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuConfigTest {

    @Test
    fun `auto maps to all layers on a gpu device and cpu otherwise`() {
        assertEquals(-1, GpuConfig.Auto.toNGpuLayers(gpuDeviceCount = 1))
        assertEquals(-1, GpuConfig.Auto.toNGpuLayers(gpuDeviceCount = 2))
        assertEquals(0, GpuConfig.Auto.toNGpuLayers(gpuDeviceCount = 0))
    }

    @Test
    fun `cpu always maps to zero`() {
        assertEquals(0, GpuConfig.Cpu.toNGpuLayers(gpuDeviceCount = 1))
        assertEquals(0, GpuConfig.Cpu.toNGpuLayers(gpuDeviceCount = 0))
    }

    @Test
    fun `all always maps to -1`() {
        assertEquals(-1, GpuConfig.All.toNGpuLayers(gpuDeviceCount = 0))
        assertEquals(-1, GpuConfig.All.toNGpuLayers(gpuDeviceCount = 1))
    }

    @Test
    fun `layers maps to the exact count regardless of device`() {
        assertEquals(3, GpuConfig.Layers(3).toNGpuLayers(gpuDeviceCount = 0))
        assertEquals(0, GpuConfig.Layers(0).toNGpuLayers(gpuDeviceCount = 2))
    }
}

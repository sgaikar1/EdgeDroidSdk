package com.sgaikar1.edgedroid.core

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Metal.MTLCreateSystemDefaultDevice

/**
 * Minimal Metal probe for the feasibility spike: proves the Kotlin/Native **Metal** platform
 * bindings resolve and compile for iosArm64. The production iOS runtime is expected to run on
 * Metal/MetalFX (MPSGraph or Metal Performance Shaders) instead of the Android Vulkan backend;
 * this answers "is Metal reachable from KMP?" with the compiler rather than a TODO.
 */
@OptIn(ExperimentalForeignApi::class)
object MetalProbe {
    /** True when a Metal device is available on this device/simulator. */
    fun hasMetalDevice(): Boolean = MTLCreateSystemDefaultDevice() != null
}
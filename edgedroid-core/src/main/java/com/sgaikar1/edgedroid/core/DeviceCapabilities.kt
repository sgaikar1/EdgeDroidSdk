package com.sgaikar1.edgedroid.core

/**
 * Snapshot of the device's hardware relevant to running an on-device model.
 * Populated by the SDK from Android APIs; injectable for tests.
 */
data class DeviceCapabilities(
    /** e.g. listOf("arm64-v8a", "armeabi-v7a") — from Build.SUPPORTED_ABIS. */
    val supportedAbis: List<String> = emptyList(),
    /** Total physical RAM in bytes. */
    val totalRamBytes: Long = 0L,
    /** Currently available RAM in bytes. */
    val availableRamBytes: Long = 0L,
    /** Free space on the app-private storage filesystem in bytes. */
    val freeStorageBytes: Long = 0L,
    /** Number of logical CPU cores. */
    val cpuCores: Int = 0,
    /** True if the device reports Vulkan hardware support (from PackageManager). */
    val vulkanSupported: Boolean = false,
)

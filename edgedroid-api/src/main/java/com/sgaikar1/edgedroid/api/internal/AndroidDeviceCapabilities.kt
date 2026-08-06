package com.sgaikar1.edgedroid.api.internal

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.app.ActivityManager
import com.sgaikar1.edgedroid.core.DeviceCapabilities

/**
 * Snapshot of device hardware from Android APIs. Built once per SDK instance.
 */
internal class AndroidDeviceCapabilities(context: Context) {

    private val appContext = context.applicationContext

    fun get(): DeviceCapabilities {
        val memoryInfo = ActivityManager.MemoryInfo().also {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getMemoryInfo(it)
        }
        return DeviceCapabilities(
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            totalRamBytes = memoryInfo.totalMem,
            availableRamBytes = memoryInfo.availMem,
            freeStorageBytes = Environment.getDataDirectory().usableSpace,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            vulkanSupported = appContext.packageManager.hasSystemFeature(
                PackageManager.FEATURE_VULKAN_HARDWARE_VERSION,
            ),
        )
    }
}

package com.sgaikar1.edgedroid.core

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSURL

internal actual fun availableProcessors(): Int =
    NSProcessInfo.processInfo.activeProcessorCount.toInt()

/**
 * iOS actual of the [PlatformServices] seam — a real Foundation-backed implementation (no stub):
 * Documents directory via NSFileManager and core count via NSProcessInfo. This is the template
 * for the production iOS port of the file-path surface that `Cache`/`ModelStorage` need.
 */
class IosPlatformServices : PlatformServices {
    override val appDataDirectory: String
        get() = (NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
            .firstOrNull() as? NSURL)?.path ?: ""

    override val cpuCoreCount: Int
        get() = availableProcessors()
}
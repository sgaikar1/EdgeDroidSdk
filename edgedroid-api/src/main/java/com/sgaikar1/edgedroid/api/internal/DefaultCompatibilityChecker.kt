package com.sgaikar1.edgedroid.api.internal

import com.sgaikar1.edgedroid.api.RuntimeRegistry
import com.sgaikar1.edgedroid.api.RuntimeSpec
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.core.Capability
import com.sgaikar1.edgedroid.core.CompatibilityChecker
import com.sgaikar1.edgedroid.core.CompatibilityIssue
import com.sgaikar1.edgedroid.core.CompatibilityReport
import com.sgaikar1.edgedroid.core.CompatibilitySeverity
import com.sgaikar1.edgedroid.core.DeviceCapabilities
import com.sgaikar1.edgedroid.core.GpuConfig
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.ModelStorage

/**
 * Implements the device-vs-model compatibility checks.
 *
 * Hard errors (block): insufficient storage, no runtime for the format, model file missing.
 * Warnings: low RAM, ABI mismatch, unknown model size. Info: CPU cores.
 */
internal class DefaultCompatibilityChecker(
    private val device: DeviceCapabilities,
    private val storage: ModelStorage,
    private val registry: RuntimeRegistry,
    private val spec: RuntimeSpec,
    private val gpuConfig: GpuConfig = GpuConfig.Auto,
    private val log: LogProvider = LogProvider.NO_OP,
) : CompatibilityChecker {

    override fun check(
        model: Model,
        requiredCapabilities: Set<Capability>,
    ): CompatibilityReport {
        val issues = mutableListOf<CompatibilityIssue>()
        val alreadyDownloaded = storage.isDownloaded(model)

        // 1. Runtime exists for the format + required capabilities.
        val plugin = registry.resolve(spec, model.format, requiredCapabilities)
        if (plugin == null) {
            issues += CompatibilityIssue(
                code = CompatibilityIssue.CODE_NO_RUNTIME,
                severity = CompatibilitySeverity.ERROR,
                message = "No runtime registered that supports format '${model.format}'" +
                    (if (requiredCapabilities.isEmpty()) "" else " and capabilities $requiredCapabilities"),
            )
        }

        // 2. Storage — only relevant if we actually need to download.
        if (!alreadyDownloaded) {
            val size = model.sizeBytes
            if (size == null) {
                issues += CompatibilityIssue(
                    code = CompatibilityIssue.CODE_UNKNOWN_MODEL_SIZE,
                    severity = CompatibilitySeverity.WARNING,
                    message = "Model size is unknown; free storage cannot be verified before download.",
                )
            } else {
                val needed = size + STORAGE_HEADROOM_BYTES
                if (device.freeStorageBytes < needed) {
                    issues += CompatibilityIssue(
                        code = CompatibilityIssue.CODE_INSUFFICIENT_STORAGE,
                        severity = CompatibilitySeverity.ERROR,
                        message = "Not enough storage: need ~${formatBytes(needed)}, " +
                            "have ${formatBytes(device.freeStorageBytes)} free.",
                    )
                }
            }
        } else {
            log.log(LogProvider.Level.DEBUG, TAG, "Model '${model.id}' already on device; skipping storage check")
        }

        // 3. Model file reachable.
        if (!alreadyDownloaded && model.downloadUrl.isNullOrBlank()) {
            issues += CompatibilityIssue(
                code = CompatibilityIssue.CODE_MODEL_FILE_MISSING,
                severity = CompatibilitySeverity.ERROR,
                message = "Model '${model.id}' is neither on device nor has a downloadUrl.",
            )
        }

        // 4. RAM heuristic — warn only.
        val size = model.sizeBytes
        if (size != null && size > 0 && device.totalRamBytes > 0 &&
            size > device.totalRamBytes * LOW_RAM_THRESHOLD
        ) {
            issues += CompatibilityIssue(
                code = CompatibilityIssue.CODE_LOW_RAM,
                severity = CompatibilitySeverity.WARNING,
                message = "Model (~${formatBytes(size)}) is large relative to device RAM " +
                    "(${formatBytes(device.totalRamBytes)}); generation may be slow.",
            )
        }

        // 5. ABI overlap with the chosen runtime — warn only.
        val abis = plugin?.supportedAbis
        if (plugin != null && !abis.isNullOrEmpty() && device.supportedAbis.isNotEmpty() &&
            abis.none { it in device.supportedAbis }
        ) {
            issues += CompatibilityIssue(
                code = CompatibilityIssue.CODE_ABI_MISMATCH,
                severity = CompatibilitySeverity.WARNING,
                message = "Runtime '${plugin.id}' ships ABIs $abis but this device reports " +
                    "${device.supportedAbis}; it may fail to load.",
            )
        }

        // 6. GPU policy — only matters when the app explicitly asked for GPU offload.
        if (gpuConfig is GpuConfig.All || gpuConfig is GpuConfig.Layers) {
            if (!device.vulkanSupported) {
                issues += CompatibilityIssue(
                    code = CompatibilityIssue.CODE_NO_VULKAN,
                    severity = CompatibilitySeverity.WARNING,
                    message = "GPU offload requested but this device reports no Vulkan support; " +
                        "the runtime will fall back to CPU.",
                )
            }
        }

        // 7. CPU cores — informational.
        issues += CompatibilityIssue(
            code = CompatibilityIssue.CODE_CPU_CORES,
            severity = CompatibilitySeverity.INFO,
            message = "Device has ${device.cpuCores} CPU cores.",
        )

        return CompatibilityReport(model, issues)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "$bytes B"
    }

    companion object {
        private const val TAG = "EdgeDroid.Compatibility"
        private const val STORAGE_HEADROOM_BYTES = 256L * 1024 * 1024
        private const val LOW_RAM_THRESHOLD = 0.75
    }
}

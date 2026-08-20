package com.sgaikar1.edgedroid.core

/**
 * Outcome of a device-vs-model compatibility check. Hard failures ([CompatibilitySeverity.ERROR])
 * are guaranteed to break download/load; warnings let the app decide.
 */
data class CompatibilityReport(
    val model: Model,
    val issues: List<CompatibilityIssue> = emptyList(),
) {
    val errors: List<CompatibilityIssue> get() = issues.filter { it.severity == CompatibilitySeverity.ERROR }
    val warnings: List<CompatibilityIssue> get() = issues.filter { it.severity == CompatibilitySeverity.WARNING }
    val info: List<CompatibilityIssue> get() = issues.filter { it.severity == CompatibilitySeverity.INFO }

    /** True if the model can be downloaded given device storage + registered runtimes. */
    val isDownloadable: Boolean
        get() = errors.none { it.code in BLOCKING_DOWNLOAD_CODES }

    /** True if the model can be loaded once present on disk. */
    val isLoadable: Boolean
        get() = errors.none { it.code in BLOCKING_LOAD_CODES }

    companion object {
        val BLOCKING_DOWNLOAD_CODES = setOf(
            CompatibilityIssue.CODE_INSUFFICIENT_STORAGE,
            CompatibilityIssue.CODE_NO_RUNTIME,
        )
        val BLOCKING_LOAD_CODES = setOf(
            CompatibilityIssue.CODE_NO_RUNTIME,
            CompatibilityIssue.CODE_MODEL_FILE_MISSING,
        )
    }
}

enum class CompatibilitySeverity { ERROR, WARNING, INFO }

data class CompatibilityIssue(
    val code: String,
    val severity: CompatibilitySeverity,
    val message: String,
) {
    companion object {
        const val CODE_INSUFFICIENT_STORAGE = "insufficient_storage"
        const val CODE_NO_RUNTIME = "no_runtime_for_format"
        const val CODE_MODEL_FILE_MISSING = "model_file_missing"
        const val CODE_LOW_RAM = "low_ram"
        const val CODE_ABI_MISMATCH = "abi_mismatch"
        const val CODE_NO_VULKAN = "no_vulkan_gpu"
        const val CODE_UNKNOWN_MODEL_SIZE = "unknown_model_size"
        const val CODE_CPU_CORES = "cpu_cores"
    }
}

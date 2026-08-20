package com.sgaikar1.edgedroid.sample

import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.DeviceCapabilities
import java.io.Serializable

/** Whether a candidate model/file can realistically run on the current device. */
enum class DeviceFit {
    FITS,
    LARGE_FOR_RAM,
    BLOCKED_STORAGE,
    BLOCKED_RUNTIME,
}

/** A model card from the HuggingFace Hub API. [eligibleFiles] are the files our SDK can use. */
data class HfModelSummary(
    val id: String,
    val downloads: Long,
    val likes: Long,
    val eligibleFiles: List<HfFile> = emptyList(),
    /** Device-fit status of [bestFile]; null when there are no eligible files. */
    val deviceFit: DeviceFit? = null,
    val deviceFitReason: String? = null,
) {
    /** Smallest eligible file — the most mobile-friendly pick. */
    val bestFile: HfFile? get() = eligibleFiles.minByOrNull { it.size }
    val eligibleCount: Int get() = eligibleFiles.size

    /** Approximate total parameters (billions) parsed from the repo id; null if unknown. */
    val paramsB: Float?
        get() = parseParamsBillion(id)
}

/** Best-effort parameter count from a repo id like `Qwen3-30B-A3B`, `smollm2-360m`, `llama-3.2-3b`. */
fun parseParamsBillion(modelId: String): Float? {
    val id = modelId.lowercase()
    Regex("""(\d+(?:\.\d+)?)b""").findAll(id)
        .map { it.groupValues[1].toFloat() }
        .maxOrNull()
        ?.let { return it }
    Regex("""(\d+)m""").findAll(id)
        .map { it.groupValues[1].toFloat() / 1000f }
        .maxOrNull()
        ?.let { return it }
    return null
}

/** A file inside an HF repo. */
data class HfFile(
    val name: String,
    val size: Long,
)

/**
 * A model the user picked from the HF browser: a specific repo file + format.
 */
data class HfModelSelection(
    val repoId: String,
    val fileName: String,
    val format: ModelFormat,
    val sizeBytes: Long?,
) : Serializable {
    val label: String get() = "$repoId / $fileName"
}

/** GGUF files that are vision encoders / embeddings / rerankers, not chat LLMs. */
private val GGUF_NON_CHAT = listOf(
    "mmproj", "projector", "clip", "vit", "vision", "mtp",
    "embed", "bge", "mxbai", "nomic", "minilm", "roberta", "rerank", "text-embedding",
)

/** ONNX files that are model components (encoder/decoder/clip), not a standalone runnable model. */
private val ONNX_NON_MODEL = listOf(
    "encoder", "decoder", "clip", "vit", "vision", "preprocessor", "tokenizer", "unet", "vae", "pooler",
)

/** PTE files that are components (tokenizer, metadata), not a standalone runnable module. */
private val PTE_NON_MODEL = listOf(
    "tokenizer", "metadata", "preprocessor", "vocab", "bpe", "spm",
)

/**
 * True if [name] is a file the SDK can actually load for [format] and that fits on a phone:
 * chat-capable GGUF (no mmproj/embedding/reranker), a standalone ONNX model, or a `.pte`
 * ExecuTorch module, and (when [maxBytes] > 0) no larger than [maxBytes].
 */
fun isEligibleFile(name: String, format: ModelFormat, maxBytes: Long): Boolean {
    val lower = name.lowercase()
    val extOk = when (format) {
        ModelFormat.ONNX -> lower.endsWith(".onnx")
        ModelFormat.PTE -> lower.endsWith(".pte")
        else -> lower.endsWith(".gguf")
    }
    if (!extOk) return false
    val blocked = when (format) {
        ModelFormat.ONNX -> ONNX_NON_MODEL.any { lower.contains(it) }
        ModelFormat.PTE -> PTE_NON_MODEL.any { lower.contains(it) }
        else -> GGUF_NON_CHAT.any { lower.contains(it) }
    }
    return !blocked
}

/** Whether a runtime for [format] exists on this device (all sample runtimes ship arm64-v8a). */
private fun runtimeAvailable(format: ModelFormat, caps: DeviceCapabilities): Boolean {
    if (caps.supportedAbis.isEmpty()) return true
    val runtimeAbis = when (format) {
        ModelFormat.ONNX -> setOf("arm64-v8a", "x86_64", "armeabi-v7a")
        // ExecuTorch's official Android AAR ships exactly these two ABIs.
        ModelFormat.PTE -> setOf("arm64-v8a", "x86_64")
        else -> setOf("arm64-v8a", "x86_64")
    }
    return runtimeAbis.any { it in caps.supportedAbis }
}

/**
 * Classify a candidate file against this device: blocked (storage/runtime) vs. fitting, and
 * flag files that are large relative to RAM (a warning, not a block). Returns the status plus
 * a short human-readable reason for the UI.
 */
fun deviceFit(
    file: HfFile,
    format: ModelFormat,
    caps: DeviceCapabilities,
): Pair<DeviceFit, String> {
    if (!runtimeAvailable(format, caps)) {
        return DeviceFit.BLOCKED_RUNTIME to "No runtime for ${format.name} on this device"
    }
    val size = file.size
    if (size > 0L) {
        if (caps.freeStorageBytes > 0L &&
            size + SampleConfig.STORAGE_HEADROOM_BYTES > caps.freeStorageBytes
        ) {
            return DeviceFit.BLOCKED_STORAGE to
                "Needs ${formatBytes(size)}, only ${formatBytes(caps.freeStorageBytes)} free"
        }
        if (caps.totalRamBytes > 0L && size > caps.totalRamBytes * LOW_RAM_THRESHOLD) {
            return DeviceFit.LARGE_FOR_RAM to
                "Large for this device's RAM (${formatBytes(caps.totalRamBytes)}) — may be slow"
        }
    }
    return DeviceFit.FITS to "Runs on this device"
}

private const val LOW_RAM_THRESHOLD = 0.75

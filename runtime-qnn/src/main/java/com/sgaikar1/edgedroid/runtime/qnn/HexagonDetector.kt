package com.sgaikar1.edgedroid.runtime.qnn

import android.content.Context
import android.os.Build

/**
 * Detects the Qualcomm Hexagon DSP architecture at runtime from public Android APIs, with a
 * best-effort fallback to the (hidden) `ro.soc.model` system property via reflection.
 *
 * The detection is deliberately conservative: it only reports an arch when it can map a
 * well-known Snapdragon SoC model (`SMxxxx`) or Qualcomm platform codename to a Hexagon
 * generation. Anything else reports [HexagonArch.UNKNOWN] — the runtime then warns that NPU
 * kernels are unavailable and GenieX can at most run on CPU/GPU.
 */
object HexagonDetector {

    /**
     * Detects the arch on the current device. Safe to call from any thread.
     */
    fun detect(context: Context): HexagonArch {
        @Suppress("DEPRECATION")
        return detect(
            socModel = Build.SOC_MODEL,
            hardware = Build.HARDWARE,
            board = Build.BOARD,
            device = Build.DEVICE,
            product = Build.PRODUCT,
            systemSocModel = readSystemProperty("ro.soc.model"),
        )
    }

    /**
     * Pure classifier, injectable for tests. All inputs are best-effort; the detection
     * succeeds as soon as any source maps to a known arch.
     */
    internal fun detect(
        socModel: String?,
        hardware: String?,
        board: String?,
        device: String?,
        product: String?,
        systemSocModel: String? = null,
    ): HexagonArch {
        classifySocModel(socModel)?.let { return it }
        classifySocModel(systemSocModel)?.let { return it }
        classifyPlatform(hardware, board, device, product)?.let { return it }
        return HexagonArch.UNKNOWN
    }

    /**
     * Maps an SoC model string (e.g. "SM8550", "SM8750-AB", "SM8850") to a Hexagon arch.
     * Matching is prefix-based and case/separator-insensitive ("SM8750AB" == "sm8750-ab").
     */
    internal fun classifySocModel(socModel: String?): HexagonArch? {
        if (socModel.isNullOrBlank()) return null
        val normalized = socModel.uppercase().filter { it.isLetterOrDigit() }
        if (!normalized.startsWith("SM")) return null
        return SOC_MODEL_TO_ARCH.entries.firstOrNull { (model, _) ->
            normalized.startsWith(model)
        }?.value
    }

    /**
     * Maps Qualcomm platform codenames (exposed via `Build.BOARD` / `Build.DEVICE` /
     * `Build.PRODUCT`, e.g. "sun", "kalama") to a Hexagon arch. Only consulted when the
     * device identifies as Qualcomm (`hardware` contains "qcom") to avoid false positives.
     */
    private fun classifyPlatform(hardware: String?, board: String?, device: String?, product: String?): HexagonArch? {
        if (hardware?.contains("qcom", ignoreCase = true) != true) return null
        val tokens = (board + " " + device + " " + product)
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .toSet()
        return PLATFORM_TO_ARCH.entries.firstOrNull { (codename, _) -> codename in tokens }?.value
    }

    private fun readSystemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getMethod("get", String::class.java)
        method.invoke(null, key) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private val SOC_MODEL_TO_ARCH: Map<String, HexagonArch> = linkedMapOf(
        "SM8450" to HexagonArch.V73,
        "SM8475" to HexagonArch.V73,
        "SM8550" to HexagonArch.V75,
        "SM8650" to HexagonArch.V77,
        "SM8635" to HexagonArch.V77,
        "SM8675" to HexagonArch.V77,
        "SM8750" to HexagonArch.V79,
        "SM8850" to HexagonArch.V81,
    )

    private val PLATFORM_TO_ARCH: Map<String, HexagonArch> = linkedMapOf(
        "waipio" to HexagonArch.V73,      // SM8450 (Snapdragon 8 Gen 1)
        "kalama" to HexagonArch.V75,      // SM8550 (Snapdragon 8 Gen 2)
        "pineapple" to HexagonArch.V77,   // SM8650 (Snapdragon 8 Gen 3)
        "sun" to HexagonArch.V79,         // SM8750 (Snapdragon 8 Elite)
        "dibda" to HexagonArch.V81,       // SM8850 (Snapdragon 8 Elite Gen 5)
    )
}
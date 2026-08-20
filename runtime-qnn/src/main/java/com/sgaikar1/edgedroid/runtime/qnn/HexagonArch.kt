package com.sgaikar1.edgedroid.runtime.qnn

/**
 * Qualcomm Hexagon DSP / NPU micro-architecture generations relevant to QNN (HTP) inference.
 *
 * ```
 *   v73  — Snapdragon 8 Gen 1      (SM8450)
 *   v75  — Snapdragon 8 Gen 2      (SM8550)
 *   v77  — Snapdragon 8 Gen 3      (SM8650)
 *   v79  — Snapdragon 8 Elite      (SM8750)
 *   v81  — Snapdragon 8 Elite Gen5 (SM8850)
 * ```
 *
 * The `runtime-qnn` AAR (Qualcomm GenieX) ships QNN HTP kernels for **v79 and v81** only;
 * older arches can still run GGUF models through GenieX's bundled llama.cpp runtime on
 * CPU/GPU (hybrid), just not the dedicated NPU kernels.
 */
enum class HexagonArch(val version: Int) {
    V73(73),
    V75(75),
    V77(77),
    V79(79),
    V81(81),

    /** No Qualcomm Hexagon DSP was detected on this device. */
    UNKNOWN(0),
    ;

    /** True when the device reports a Qualcomm Hexagon DSP at all. */
    val isHexagon: Boolean
        get() = this != UNKNOWN

    /**
     * True when the bundled GenieX AAR ships QNN HTP kernels for this arch (v79 / v81).
     * This is the "NPU-capable" flag — the same shape as `DeviceCapabilities.vulkanSupported`.
     */
    val isNpuCapable: Boolean
        get() = this == V79 || this == V81
}
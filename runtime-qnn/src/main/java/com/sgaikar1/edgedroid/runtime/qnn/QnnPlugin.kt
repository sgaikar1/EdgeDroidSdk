package com.sgaikar1.edgedroid.runtime.qnn

import android.content.Context
import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.Capability
import com.sgaikar1.edgedroid.core.Runtime
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimePlugin

/**
 * Qualcomm Hexagon NPU runtime via the GenieX Android SDK (Qualcomm AI Engine Direct / QNN).
 * Registered as a plain [RuntimePlugin] — the SDK core is never modified.
 *
 * ```kotlin
 * val sdk = EdgeDroid.Builder(context)
 *     .registerRuntime(QnnPlugin(context))       // AUTO routes GGUF/QNN models to it
 *     // or .runtime(Runtime.byId("qnn"))        // pin to the NPU runtime explicitly
 *     .build()
 * ```
 *
 * Selection is format/capability-driven ([RuntimeSelector]); the device-arch flags below let an
 * app decide whether to register the plugin in the first place:
 *
 * - [hexagonArch] — detected Hexagon generation (v73/v75/v77/v79/v81, or [HexagonArch.UNKNOWN]).
 * - [npuSupported] — VULKAN-style flag: true when the bundled GenieX AAR ships QNN HTP kernels
 *   for this device's arch (v79/v81). Older arches can still run GGUF through GenieX's bundled
 *   llama.cpp runtime on CPU/GPU (hybrid), just not the dedicated NPU kernels.
 */
class QnnPlugin(context: Context) : RuntimePlugin {

    private val appContext = context.applicationContext

    /** Hexagon arch detected at construction time from the device. */
    val hexagonArch: HexagonArch = HexagonDetector.detect(appContext)

    /**
     * "VULKAN-style" capability flag: true when the device's Hexagon arch is covered by the
     * bundled QNN HTP kernels (v79 / v81 — Snapdragon 8 Elite / 8 Elite Gen 5).
     */
    val npuSupported: Boolean
        get() = hexagonArch.isNpuCapable

    override val id: String = "qnn"
    override val version: String = BuildConfig.SDK_VERSION
    override val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.GGUF, ModelFormat.QNN)
    override val capabilities: Set<Capability> = setOf(Capability.STREAMING, Capability.VISION)
    override val supportedAbis: Set<String> = setOf("arm64-v8a")

    override suspend fun create(config: RuntimeConfig): Runtime = QnnRuntime(config, appContext)
}
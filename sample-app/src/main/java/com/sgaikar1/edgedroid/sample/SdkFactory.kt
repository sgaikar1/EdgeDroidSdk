package com.sgaikar1.edgedroid.sample

import android.content.Context
import android.util.Log
import com.sgaikar1.edgedroid.api.EdgeDroid
import com.sgaikar1.edgedroid.api.Runtime
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.runtime.llama.LlamaPlugin
import com.sgaikar1.edgedroid.runtime.onnx.OnnxPlugin
import com.sgaikar1.edgedroid.runtime.tts.KokoroTtsPlugin
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Builds an [EdgeDroid] from a [SampleConfig] — runtime, model, threading, memory/GPU,
 * ONNX execution provider and download options all come from the UI.
 */
object SdkFactory {

    private val logger = object : LogProvider {
        override fun log(level: LogProvider.Level, tag: String, message: String, throwable: Throwable?) {
            Log.d(tag, "[$level] $message")
        }
    }

    fun build(context: Context, config: SampleConfig): EdgeDroid {
        val model = config.model
        val builder = EdgeDroid.Builder(context)
            .runtime(
                Runtime.plugin(
                    if (model.runtime == SampleRuntime.LLAMA) LlamaPlugin() else OnnxPlugin(),
                ),
            )
            .model(modelToSdkModel(context, model, config))
            .threading { threads(config.threads) }
            .download {
                maxRetries(config.maxRetries)
                timeout(config.downloadTimeoutSeconds.seconds)
            }
            .tts(KokoroTtsPlugin(), ttsModel(context))
            .logging(logger)

        when (model.runtime) {
            SampleRuntime.LLAMA -> {
                builder.memory {
                    contextSize(config.contextSize)
                    gpu(config.gpu)
                }
            }
            SampleRuntime.ONNX -> {
                config.executionProvider?.let { builder.extra("executionProvider", it) }
            }
        }
        return builder.build()
    }

    private fun modelToSdkModel(context: Context, model: SampleModel, config: SampleConfig): Model {
        val metadata = model.metadata.toMutableMap()
        if (model.runtime == SampleRuntime.ONNX) {
            metadata["tokenizerPath"] = ensureOnnxTokenizer(context, model.id).absolutePath
        }
        model.mmprojUrl?.let { mmprojUrl ->
            metadata["mmprojPath"] = ensureMmproj(context, model.id, mmprojUrl).absolutePath
        }
        return Model.remote(
            id = model.id,
            name = model.label,
            url = model.url,
            sizeBytes = model.sizeBytes,
            format = model.format,
            metadata = metadata,
        )
    }

    /** Fetch the mmproj vision encoder alongside a vision-capable GGUF model. */
    private fun ensureMmproj(context: Context, modelId: String, url: String): File {
        val safe = modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(context.filesDir, "$safe-mmproj.gguf")
        if (!file.exists()) {
            HfHubClient.downloadTo(url, file)
        }
        return file
    }

    /** Bundled MiniLM tokenizer for the preset; for custom HF ONNX models fetch the repo's. */
    private fun ensureOnnxTokenizer(context: Context, modelId: String): File {
        val safe = modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(context.filesDir, "$safe-tokenizer.json")
        if (!file.exists()) {
            val source = if (modelId == "all-minilm-l6-v2") {
                context.assets.open("all-minilm-tokenizer.json").use { it.readBytes() }
            } else {
                HfHubClient.fetchBytes(HfHubClient.resolveUrl(modelId, "tokenizer.json"))
            }
            file.writeBytes(source)
        }
        return file
    }

    /**
     * Kokoro TTS model. The default voice (`af_heart.bin`) ships with the `runtime-tts` module
     * as an asset; we copy it to app storage once and point the runtime at it via
     * `metadata['voicePath']`. The ~86 MB ONNX model itself is downloaded on demand by the SDK
     * the first time [com.sgaikar1.edgedroid.api.EdgeDroid.speak] is called.
     */
    private fun ttsModel(context: Context): Model {
        val voice = File(context.filesDir, "kokoro-af_heart.bin")
        if (!voice.exists()) {
            context.assets.open("voices/af_heart.bin").use { input ->
                voice.writeBytes(input.readBytes())
            }
        }
        val k = SampleModels.KOKORO_TTS
        return Model.remote(
            id = k.id,
            name = k.label,
            url = k.url,
            sizeBytes = k.sizeBytes,
            format = k.format,
            metadata = k.metadata + mapOf("voicePath" to voice.absolutePath),
        )
    }
}

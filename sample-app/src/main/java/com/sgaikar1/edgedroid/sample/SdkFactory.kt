package com.sgaikar1.edgedroid.sample

import android.content.Context
import android.util.Log
import com.sgaikar1.edgedroid.api.LlmSdk
import com.sgaikar1.edgedroid.api.Runtime
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.runtime.llama.LlamaPlugin
import com.sgaikar1.edgedroid.runtime.onnx.OnnxPlugin
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Builds an [LlmSdk] from a [SampleConfig] — runtime, model, threading, memory/GPU,
 * ONNX execution provider and download options all come from the UI.
 */
object SdkFactory {

    private val logger = object : LogProvider {
        override fun log(level: LogProvider.Level, tag: String, message: String, throwable: Throwable?) {
            Log.d(tag, "[$level] $message")
        }
    }

    fun build(context: Context, config: SampleConfig): LlmSdk {
        val model = config.model
        val builder = LlmSdk.Builder(context)
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
        return Model.remote(
            id = model.id,
            name = model.label,
            url = model.url,
            sizeBytes = model.sizeBytes,
            format = model.format,
            metadata = metadata,
        )
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
}

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
            metadata["tokenizerPath"] = ensureTokenizerAsset(context).absolutePath
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

    /** Copies the bundled MiniLM tokenizer next to the ONNX model once. */
    private fun ensureTokenizerAsset(context: Context): File {
        val file = File(context.filesDir, "all-minilm-tokenizer.json")
        if (!file.exists()) {
            context.assets.open("all-minilm-tokenizer.json").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file
    }
}

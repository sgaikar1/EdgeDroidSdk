package com.sgaikar1.edgedroid.sample

import android.content.Context
import android.util.Log
import com.sgaikar1.edgedroid.api.EdgeDroid
import com.sgaikar1.edgedroid.api.Runtime
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.runtime.executorch.ExecuTorchPlugin
import com.sgaikar1.edgedroid.runtime.llama.LlamaPlugin
import com.sgaikar1.edgedroid.runtime.onnx.OnnxPlugin
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
                    when (model.runtime) {
                        SampleRuntime.LLAMA -> LlamaPlugin()
                        SampleRuntime.ONNX -> OnnxPlugin()
                        SampleRuntime.EXECUTORCH -> ExecuTorchPlugin()
                    },
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
            SampleRuntime.EXECUTORCH -> {
                builder.memory {
                    contextSize(config.contextSize)
                    // ExecuTorch LLM modules mmap the .pte by default; no GPU layers to tune.
                    mmap(true)
                }
                if (model.visionCapable) {
                    builder.extra("modelType", "vision")
                }
                // tokenizerPath is carried in the SDK Model metadata (see modelToSdkModel).
            }
        }
        return builder.build()
    }

    private fun modelToSdkModel(context: Context, model: SampleModel, config: SampleConfig): Model {
        val metadata = model.metadata.toMutableMap()
        when (model.runtime) {
            SampleRuntime.ONNX -> {
                metadata["tokenizerPath"] = ensureOnnxTokenizer(context, model.id).absolutePath
            }
            SampleRuntime.EXECUTORCH -> {
                // A PTE export ships with a paired tokenizer file. Use the model's declared
                // tokenizerUrl when present, otherwise discover one in the same HF repo (this is
                // the path HF-browser PTE picks take, since fromHf() has no tokenizer URL).
                val tokenizerUrl = metadata["tokenizerUrl"]
                    ?: discoverExecutorchTokenizerUrl(model.id)
                if (tokenizerUrl != null) {
                    metadata["tokenizerPath"] = ensureExecutorchTokenizer(context, model.id, tokenizerUrl).absolutePath
                }
            }
            SampleRuntime.LLAMA -> Unit
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

    /** Fetch the tokenizer file that ships beside an ExecuTorch `.pte` export. */
    private fun ensureExecutorchTokenizer(context: Context, modelId: String, url: String): File {
        val safe = modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(context.filesDir, "$safe-executorch-tokenizer")
        if (!file.exists()) {
            HfHubClient.downloadTo(url, file)
        }
        return file
    }

    /**
     * Best-effort discovery of the tokenizer that ships beside a `.pte` export in the same HF
     * repo, for browser-picked PTE models that carry no explicit `tokenizerUrl`. Returns null
     * (so the runtime reports a clear load error) when none can be found.
     */
    private fun discoverExecutorchTokenizerUrl(modelId: String): String? =
        runCatching {
            val names = HfHubClient.listFiles(modelId).map { it.name }
            val tokenizer = names.firstOrNull { it.endsWith(".bin") }
                ?: names.firstOrNull { it.contains("token") && it.endsWith(".json") }
            tokenizer?.let { HfHubClient.resolveUrl(modelId, it) }
        }.getOrNull()
}

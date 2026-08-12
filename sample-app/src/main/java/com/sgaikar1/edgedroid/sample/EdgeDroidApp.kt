package com.sgaikar1.edgedroid.sample

import android.app.Application
import android.util.Log
import com.sgaikar1.edgedroid.api.LlmSdk
import com.sgaikar1.edgedroid.api.Runtime
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.runtime.llama.LlamaPlugin
import com.sgaikar1.edgedroid.runtime.onnx.OnnxPlugin
import java.io.File

class EdgeDroidApp : Application() {

    lateinit var sdk: LlmSdk
        private set

    /** ONNX Runtime embeddings demo (all-MiniLM-L6-v2 int8). */
    lateinit var embeddingSdk: LlmSdk
        private set

    override fun onCreate() {
        super.onCreate()

        val logger = object : LogProvider {
            override fun log(level: LogProvider.Level, tag: String, message: String, throwable: Throwable?) {
                Log.d(tag, "[$level] $message")
            }
        }

        sdk = LlmSdk.Builder(this)
            .runtime(Runtime.plugin(LlamaPlugin()))
            .model(
                Model.remote(
                    id = "smollm2-135m-instruct",
                    name = "SmolLM2 135M Instruct (Q4_K_M)",
                    url = "https://huggingface.co/unsloth/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf",
                    sizeBytes = 105_454_144L,
                    metadata = mapOf("template" to "chatml"),
                ),
            )
            .threading { threads(4); batchThreads(4) }
            .memory { contextSize(2048); mmap(true); batchSize(256) }
            .download { maxRetries(3); timeout(kotlin.time.Duration.parse("60s")) }
            .logging(logger)
            .build()

        // Copy the bundled tokenizer next to the ONNX model and point the SDK at it.
        val tokenizerFile = File(filesDir, "all-minilm-tokenizer.json")
        if (!tokenizerFile.exists()) {
            assets.open("all-minilm-tokenizer.json").use { input ->
                tokenizerFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        embeddingSdk = LlmSdk.Builder(this)
            .runtime(Runtime.plugin(OnnxPlugin()))
            .model(
                Model.remote(
                    id = "all-minilm-l6-v2",
                    name = "all-MiniLM-L6-v2 (int8)",
                    url = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx",
                    sizeBytes = 22_972_370L,
                    format = com.sgaikar1.edgedroid.common.ModelFormat.ONNX,
                    metadata = mapOf(
                        "template" to "raw",
                        "tokenizerPath" to tokenizerFile.absolutePath,
                    ),
                ),
            )
            .threading { threads(4); batchThreads(4) }
            .extra("executionProvider", "NNAPI")
            .download { maxRetries(3); timeout(kotlin.time.Duration.parse("60s")) }
            .logging(logger)
            .build()
    }
}

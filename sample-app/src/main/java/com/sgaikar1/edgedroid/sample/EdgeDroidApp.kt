package com.sgaikar1.edgedroid.sample

import android.app.Application
import android.util.Log
import com.sgaikar1.edgedroid.api.LlmSdk
import com.sgaikar1.edgedroid.api.Runtime
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.runtime.llama.LlamaPlugin

class EdgeDroidApp : Application() {

    lateinit var sdk: LlmSdk
        private set

    override fun onCreate() {
        super.onCreate()

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
            .logging(
                object : LogProvider {
                    override fun log(level: LogProvider.Level, tag: String, message: String, throwable: Throwable?) {
                        Log.d(tag, "[$level] $message")
                    }
                },
            )
            .build()
    }
}

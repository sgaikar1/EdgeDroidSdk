package com.sgaikar1.edgedroid.runtime.qnn

import android.content.Context
import com.geniex.sdk.GenieXSdk
import com.sgaikar1.edgedroid.common.LogProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Single-point wrapper around the GenieX native SDK lifecycle. `GenieXSdk.init` is a global,
 * once-per-process initialization (plugin registration + model-manager init); calling it twice
 * is rejected by the FFI, so we guard it exactly like the GenieX sample does.
 */
internal object GenieXSession {

    @Volatile
    private var initialized = false

    /**
     * Initializes the GenieX native SDK exactly once per process. Safe to call concurrently;
     * concurrent callers block until the first init completes.
     *
     * @throws IllegalStateException when the native init fails or times out (30s).
     */
    suspend fun ensureInitialized(context: Context, log: LogProvider) {
        if (initialized) return
        withContext(Dispatchers.Default) {
            if (initialized) return@withContext
            synchronized(this) {
                if (initialized) return@synchronized
                val latch = CountDownLatch(1)
                var failure: String? = null
                GenieXSdk.getInstance().init(
                    context.applicationContext,
                    object : GenieXSdk.InitCallback {
                        override fun onSuccess() {
                            latch.countDown()
                        }

                        override fun onFailure(reason: String) {
                            failure = reason
                            latch.countDown()
                        }
                    },
                )
                if (!latch.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw IllegalStateException("GenieX SDK init timed out after $INIT_TIMEOUT_SECONDS s")
                }
                failure?.let { throw IllegalStateException("GenieX SDK init failed: $it") }
                initialized = true
                log.log(LogProvider.Level.INFO, TAG, "GenieX SDK initialized (QNN / Hexagon NPU backend)")
            }
        }
    }

    private const val TAG = "EdgeDroid.GenieX"
    private const val INIT_TIMEOUT_SECONDS = 30L
}
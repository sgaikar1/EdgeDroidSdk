package com.sgaikar1.edgedroid.sample

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class EdgeDroidApp : Application() {

    lateinit var sampleStore: SampleStore
        private set

    override fun onCreate() {
        super.onCreate()
        sampleStore = SampleStore(this)

        // The local OpenAI server is tied to the app lifecycle: it runs while the app is in
        // the foreground and stops when the app goes to the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_START -> sampleStore.onAppForeground()
                    Lifecycle.Event.ON_STOP -> sampleStore.onAppBackground()
                    else -> Unit
                }
            }
        })
    }
}
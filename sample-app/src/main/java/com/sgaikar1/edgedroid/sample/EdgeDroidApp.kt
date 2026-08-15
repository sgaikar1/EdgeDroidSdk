package com.sgaikar1.edgedroid.sample

import android.app.Application

class EdgeDroidApp : Application() {

    lateinit var sampleStore: SampleStore
        private set

    override fun onCreate() {
        super.onCreate()
        sampleStore = SampleStore(this)
    }
}

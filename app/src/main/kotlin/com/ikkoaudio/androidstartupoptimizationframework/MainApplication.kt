package com.ikkoaudio.androidstartupoptimizationframework

import android.app.Application
import com.ikkoaudio.startup.core.TaskRegistry
import com.ikkoaudio.startup.sample.provider.CoreTaskProvider

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TaskRegistry.clear()
        TaskRegistry.register(CoreTaskProvider())
    }
}

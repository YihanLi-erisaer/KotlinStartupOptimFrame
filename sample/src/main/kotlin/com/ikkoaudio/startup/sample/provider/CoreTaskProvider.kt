package com.ikkoaudio.startup.sample.provider

import com.ikkoaudio.startup.core.StartupTaskProvider
import com.ikkoaudio.startup.sample.InitDatabaseTask
import com.ikkoaudio.startup.sample.InitLoggerTask
import com.ikkoaudio.startup.sample.InitNetworkTask

class CoreTaskProvider : StartupTaskProvider {
    override fun provide() = listOf(
        InitLoggerTask(),
        InitNetworkTask(),
        InitDatabaseTask(),
    )
}

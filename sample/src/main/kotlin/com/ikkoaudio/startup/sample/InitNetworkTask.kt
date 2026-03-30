package com.ikkoaudio.startup.sample

import com.ikkoaudio.startup.core.StartupTask
import kotlinx.coroutines.delay

class InitNetworkTask : StartupTask {
    override val id: String = "network"
    override val dependencies: List<String> = listOf("logger")
    override val runOnMainThread: Boolean = false
    override val needWait: Boolean = false

    override suspend fun run() {
        delay(200)
        println("Network initialized")
    }
}

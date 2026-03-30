package com.ikkoaudio.startup.sample

import com.ikkoaudio.startup.core.StartupTask
import kotlinx.coroutines.delay

class InitDatabaseTask : StartupTask {
    override val id: String = "database"
    override val dependencies: List<String> = listOf("network")
    override val runOnMainThread: Boolean = false
    override val needWait: Boolean = true

    override suspend fun run() {
        delay(300)
        println("Database initialized")
    }
}

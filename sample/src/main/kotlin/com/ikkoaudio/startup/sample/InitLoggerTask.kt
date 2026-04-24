package com.ikkoaudio.startup.sample

import com.ikkoaudio.startup.core.ExecutionPhase
import com.ikkoaudio.startup.core.StartupTask
import kotlinx.coroutines.delay

class InitLoggerTask : StartupTask {
    override val id: String = "logger"
    override val dependencies: List<String> = emptyList()
    override val runOnMainThread: Boolean = false
    override val needWait: Boolean = true
    override val executionPhase: ExecutionPhase = ExecutionPhase.BeforeFirstFrame

    override suspend fun run() {
        delay(100)
        println("Logger initialized")
    }
}

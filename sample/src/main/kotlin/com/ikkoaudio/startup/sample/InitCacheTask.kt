package com.ikkoaudio.startup.sample

import com.ikkoaudio.startup.core.StartupTask
import kotlinx.coroutines.delay

/** 与 [InitNetworkTask] 同样依赖 logger，可与 network 并行执行以体现 DAG 优势。 */
class InitCacheTask : StartupTask {
    override val id: String = "cache"
    override val dependencies: List<String> = listOf("logger")
    override val runOnMainThread: Boolean = false
    override val needWait: Boolean = false

    override suspend fun run() {
        delay(250)
        println("Cache initialized")
    }
}
